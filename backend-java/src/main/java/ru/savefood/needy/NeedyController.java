package ru.savefood.needy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import ru.savefood.audit.AuditService;
import ru.savefood.cache.CacheService;
import ru.savefood.kyc.KycCrypto;
import ru.savefood.kyc.KycService;
import ru.savefood.needy.dto.GeoPushUpdate;
import ru.savefood.needy.dto.ModerationUpdate;
import ru.savefood.needy.dto.NeedyCreate;
import ru.savefood.needy.dto.NeedyProfileUpsert;
import ru.savefood.needy.dto.TicketCreate;
import ru.savefood.photo.PhotoModerationService;
import ru.savefood.security.Auth;
import ru.savefood.security.Authz;
import ru.savefood.security.CurrentUser;
import ru.savefood.shop.ShopRepository;
import ru.savefood.telegram.TelegramService;
import ru.savefood.upload.UploadService;
import ru.savefood.web.ApiException;
import ru.savefood.web.ClientIp;
import ru.savefood.web.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Java port of backend/needy/routes.py — the recipient surface (registration,
 * profile + eligibility document, tickets, notifications, history, ratings, impact
 * photos, GDPR export/erase) plus the public {@code GET /lots} map. Authenticated
 * routes take an {@code @Auth CurrentUser} and call {@link Authz#ensureOwnerOrAdmin}
 * for per-recipient ownership; {@code POST /needy/register} and {@code GET /lots}
 * are public.
 *
 * <p>The {@code GET /ws/needy/{id}} notification stream is a WebSocket and lives in
 * {@link NeedyWebSocketHandler}.
 */
@RestController
public class NeedyController {

    private final NeedyRepository repo;
    private final NeedyService service;
    private final ShopRepository shopRepo;
    private final CacheService cache;
    private final UploadService uploads;
    private final KycCrypto kycCrypto;
    private final KycService kycService;
    private final PhotoModerationService photoModeration;
    private final RateLimiter rateLimiter;
    private final TelegramService telegram;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final String needyUploadDir;
    private final String volunteerUploadDir;

    public NeedyController(NeedyRepository repo, NeedyService service, ShopRepository shopRepo,
                          CacheService cache, UploadService uploads, KycCrypto kycCrypto,
                          KycService kycService, PhotoModerationService photoModeration,
                          RateLimiter rateLimiter, TelegramService telegram,
                          JdbcTemplate jdbc, AuditService audit,
                          @Value("${savefood.needy-upload-dir}") String needyUploadDir,
                          @Value("${savefood.volunteer-upload-dir}") String volunteerUploadDir) {
        this.repo = repo;
        this.service = service;
        this.shopRepo = shopRepo;
        this.cache = cache;
        this.uploads = uploads;
        this.kycCrypto = kycCrypto;
        this.kycService = kycService;
        this.photoModeration = photoModeration;
        this.rateLimiter = rateLimiter;
        this.telegram = telegram;
        this.jdbc = jdbc;
        this.audit = audit;
        this.needyUploadDir = needyUploadDir;
        this.volunteerUploadDir = volunteerUploadDir;
    }

    // ── Registration ────────────────────────────────────────────────────────────

    @PostMapping("/needy/register")
    public Map<String, Object> registerNeedy(@RequestBody NeedyCreate payload, HttpServletRequest request) {
        rateLimiter.check("needy:register", ClientIp.of(request), 5);
        // An account is mandatory: a needy row without credentials can never log in.
        if (isBlank(payload.username()) || isBlank(payload.password())) {
            throw new ApiException(400, "Укажите логин и пароль");
        }
        validatePassword(payload.password());
        int needyId = service.registerNeedy(payload.name(), payload.contact(),
            payload.username(), payload.password());
        return Map.of("id", needyId);
    }

    // ── Tickets ──────────────────────────────────────────────────────────────────

    @PostMapping("/needy/{needyId}/ticket")
    public Map<String, Object> createTicket(@PathVariable int needyId, @RequestBody TicketCreate payload,
                                            @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        Map<String, Object> needy = repo.getNeedyById(needyId);
        if (needy == null) {
            throw new ApiException(404, "Needy not found");
        }
        if (!"approved".equals(needy.get("status"))) {
            throw new ApiException(403, "Account not approved yet");
        }

        boolean selfPickup = Boolean.TRUE.equals(payload.selfPickup());
        Double lat = payload.lat();
        Double lon = payload.lon();
        // Delivery tickets must carry the recipient's home coordinates, else the
        // volunteer map/route queries (lat/lon NOT NULL) never surface them.
        if (!selfPickup && (lat == null || lon == null)) {
            Map<String, Object> profile = repo.getProfile(needyId);
            if (profile != null) {
                if (lat == null) {
                    lat = asDouble(profile.get("lat"));
                }
                if (lon == null) {
                    lon = asDouble(profile.get("lon"));
                }
            }
        }

        try {
            int ticketId = service.createTicket(needyId, payload.items(), payload.address(), lat, lon,
                payload.availableTime(), payload.lotId(), payload.apartment(), payload.floorNum(),
                payload.entrance(), selfPickup);
            return Map.of("id", ticketId);
        } catch (NeedyService.TicketCreateException exc) {
            throw new ApiException(400, switch (exc.reason()) {
                case "weekly_limit" -> "Помощь можно получать не чаще раза в неделю";
                case "active_ticket_exists" ->
                    "У вас уже есть активная заявка — дождитесь её завершения";
                case "lot_required" -> "Для самовывоза нужно выбрать конкретный лот";
                case "lot_unavailable" ->
                    "Этот лот уже разобран или просрочен — выберите другой на карте";
                default -> exc.reason();
            });
        }
    }

    @GetMapping("/needy/{needyId}/tickets")
    public List<Map<String, Object>> getTickets(@PathVariable int needyId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        return repo.getTicketsByNeedyId(needyId);
    }

    @GetMapping("/needy/{needyId}/history")
    public List<Map<String, Object>> history(@PathVariable int needyId,
                                             @RequestParam(defaultValue = "20") int limit,
                                             @RequestParam(defaultValue = "0") int offset,
                                             @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        return repo.getHistory(needyId, limit, offset);
    }

    @DeleteMapping("/needy/{needyId}/ticket/{ticketId}")
    public Map<String, Object> cancelTicket(@PathVariable int needyId, @PathVariable int ticketId,
                                            @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        Integer volId = service.cancelTicket(needyId, ticketId);
        if (volId != null) {
            try {
                // After the transaction, like Python's post-cursor send.
                telegram.notifyVolunteer(volId,
                    "❌ Получатель отменил заявку #" + ticketId + " — точка снята с вашего маршрута.");
            } catch (RuntimeException ignore) {
                // best-effort
            }
        }
        return Map.of("ok", true);
    }

    @PostMapping("/needy/{needyId}/ticket/{ticketId}/rate")
    public Map<String, Object> rateDelivery(@PathVariable int needyId, @PathVariable int ticketId,
                                            @RequestParam int rating,
                                            @RequestParam(required = false) String comment,
                                            @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        if (rating < 1 || rating > 5) {
            throw new ApiException(400, "Rating must be between 1 and 5");
        }
        // Cap server-side: the frontend's maxLength is advisory for direct callers.
        String capped = comment == null ? null : comment.substring(0, Math.min(500, comment.length()));
        service.rateDelivery(needyId, ticketId, rating, capped);
        return Map.of("ok", true);
    }

    @PostMapping("/needy/{needyId}/ticket/{ticketId}/photo")
    public Map<String, Object> uploadImpactPhoto(@PathVariable int needyId, @PathVariable int ticketId,
                                                 @RequestParam MultipartFile file, @Auth CurrentUser user,
                                                 HttpServletRequest request) {
        rateLimiter.checkHourly("needy:impact_photo", ClientIp.of(request), 3);
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);

        // Reuse the volunteer uploads directory for the public impact feed.
        String filename = uploads.validateAndSave(file, volunteerUploadDir);
        String photoUrl = "/volunteer_uploads/" + filename;
        // The photo is NOT public yet: it lands 'pending' and only moderation /
        // the AI pre-check can promote it to 'approved' (§36.1).
        String oldPhoto = service.setDeliveryPhotoPending(needyId, ticketId, photoUrl);

        // The DB now points at the new file — the replaced photo (if any) is an orphan.
        if (oldPhoto != null && oldPhoto.startsWith("/volunteer_uploads/")) {
            deleteQuietly(Paths.get(volunteerUploadDir, basename(oldPhoto)));
        }

        // Fire-and-forget AI "is this actually food?" pre-check (§36.1).
        photoModeration.startPhotoCheck(ticketId, Paths.get(volunteerUploadDir, filename).toString());
        return Map.of("photo_url", photoUrl, "status", "pending");
    }

    // ── Needy profile / account ───────────────────────────────────────────────────

    @GetMapping("/needy/{needyId}")
    public Map<String, Object> getNeedy(@PathVariable int needyId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        Map<String, Object> needy = repo.getNeedyById(needyId);
        if (needy == null) {
            throw new ApiException(404, "Needy not found");
        }
        return needy;
    }

    @PatchMapping("/needy/{needyId}")
    public Map<String, Object> updateNeedy(@PathVariable int needyId, @RequestBody NeedyCreate payload,
                                           @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        Map<String, Object> updated = repo.updateNeedy(needyId, payload.name(), payload.contact());
        if (updated == null) {
            throw new ApiException(404, "Needy not found");
        }
        return updated;
    }

    @PostMapping("/needy/{needyId}/profile")
    public Map<String, Object> createProfile(@PathVariable int needyId,
                                             @RequestBody NeedyProfileUpsert payload,
                                             @Auth CurrentUser user) {
        return upsertProfile(needyId, payload, user);
    }

    @PatchMapping("/needy/{needyId}/profile")
    public Map<String, Object> patchProfile(@PathVariable int needyId,
                                            @RequestBody NeedyProfileUpsert payload,
                                            @Auth CurrentUser user) {
        return upsertProfile(needyId, payload, user);
    }

    @GetMapping("/needy/{needyId}/profile")
    public Map<String, Object> getProfile(@PathVariable int needyId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        Map<String, Object> profile = repo.getProfile(needyId);
        if (profile == null) {
            throw new ApiException(404, "Profile not found");
        }
        return profile;
    }

    @PostMapping("/needy/{needyId}/profile/upload")
    public Map<String, Object> uploadProfileDocument(@PathVariable int needyId,
                                                     @RequestParam(required = false) MultipartFile file,
                                                     @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        Map<String, Object> needy = repo.getNeedyById(needyId);
        if (needy == null) {
            throw new ApiException(404, "Needy not found");
        }
        String filename = uploads.validateAndSave(file, needyUploadDir, true);
        Path path = Paths.get(needyUploadDir, filename);
        // Encrypt the eligibility document at rest immediately (§58): on disk it is
        // only ever ciphertext; the AI verification decrypts it in memory.
        kycCrypto.encryptFile(path.toString());
        Map<String, Object> profile = repo.createOrUpdateProfile(needyId, null, null, null, null,
            "/needy_uploads/" + filename, null, null, null, null, null, null, null);
        // Fully automated KYC: fire-and-forget AI check that auto-decides (no human).
        String name = needy.get("name") == null ? "" : needy.get("name").toString();
        kycService.startKycCheck(needyId, path.toString(), name);
        return profile;
    }

    // ── KYC: document access (owner/admin) + moderation & recheck (admin) ──────────

    /**
     * Serve the recipient's eligibility document, decrypted in memory (§58 — on disk
     * it's ciphertext). Owner or admin only; the file is never exposed via a public
     * URL. Used by the recipient to review their upload and by the moderator's queue.
     */
    @GetMapping("/needy/{needyId}/document")
    public ResponseEntity<byte[]> getDocument(@PathVariable int needyId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        Map<String, Object> profile = repo.getProfile(needyId);
        String docUrl = profile == null ? null : (String) profile.get("document");
        if (docUrl == null || docUrl.isBlank()) {
            throw new ApiException(404, "Документ не найден");
        }
        Path path = Paths.get(needyUploadDir, basename(docUrl));
        if (!Files.isRegularFile(path)) {
            throw new ApiException(404, "Документ не найден");
        }
        byte[] content;
        try {
            content = kycCrypto.readDecrypted(path.toString());
        } catch (Exception e) {
            throw new ApiException(500, "Не удалось прочитать документ");
        }
        return ResponseEntity.ok().contentType(mediaTypeFor(basename(docUrl))).body(content);
    }

    /**
     * Moderator decision on a pending recipient (hybrid KYC, §58): approve or reject.
     * Confident {@code likely_ok} was already auto-approved; everything else waits
     * here for a human. On a decision the eligibility document is deleted (§5 — PII).
     */
    @PatchMapping("/needy/{needyId}/moderation")
    public Map<String, Object> moderateNeedy(@PathVariable int needyId,
            @RequestBody ModerationUpdate payload, @Auth CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(403, "Только администратор");
        }
        String status = payload.status();
        if (!"approved".equals(status) && !"rejected".equals(status)) {
            throw new ApiException(400, "status must be 'approved' or 'rejected'");
        }
        if (repo.setNeedyStatus(needyId, status, "pending") == null) {
            throw new ApiException(409, "Заявка уже промодерирована или не найдена");
        }
        deleteDocument(needyId);
        boolean approved = "approved".equals(status);
        String msg = approved
            ? "Ваша анкета одобрена модератором — можете создавать заявки на получение продуктов."
            : "Ваша анкета отклонена. Загрузите корректный документ, подтверждающий право на помощь.";
        jdbc.update(
            "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
            needyId, approved ? "moderation_approved" : "moderation_rejected", msg, OffsetDateTime.now());
        try {
            telegram.notifyNeedy(needyId, (approved ? "✅ " : "⚠️ ") + msg);
        } catch (RuntimeException ignore) {
            // best-effort
        }
        audit.log(user.sub(), "needy_moderation", "needy", needyId,
            "Admin set needy #" + needyId + " to " + status);
        return Map.of("ok", true, "status", status);
    }

    /**
     * Re-run the AI verdict on a still-present document (§38.2 «второе мнение»),
     * synchronously, and return the fresh verdict. Only possible while the document
     * exists (a decision deletes it, §5). Admin only.
     */
    @PostMapping("/needy/{needyId}/kyc_recheck")
    public Map<String, Object> recheckNeedy(@PathVariable int needyId, @Auth CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(403, "Только администратор");
        }
        Map<String, Object> profile = repo.getProfile(needyId);
        String docUrl = profile == null ? null : (String) profile.get("document");
        if (docUrl == null || docUrl.isBlank()) {
            throw new ApiException(404, "Документ уже удалён — перепроверка недоступна");
        }
        Map<String, Object> needy = repo.getNeedyById(needyId);
        String name = needy == null || needy.get("name") == null ? "" : needy.get("name").toString();
        kycService.recheckNeedy(needyId, Paths.get(needyUploadDir, basename(docUrl)).toString(), name);
        audit.log(user.sub(), "kyc_recheck", "needy", needyId, "Admin re-ran AI KYC for needy #" + needyId);
        Map<String, Object> updated = repo.getNeedyById(needyId);
        return Map.of(
            "kyc_verdict", updated.get("kyc_verdict") == null ? "unchecked" : updated.get("kyc_verdict"),
            "kyc_score", updated.get("kyc_score") == null ? "" : updated.get("kyc_score"),
            "kyc_notes", updated.get("kyc_notes") == null ? "" : updated.get("kyc_notes"));
    }

    /** Delete the recipient's eligibility document from disk and clear the column (§5). */
    private void deleteDocument(int needyId) {
        Map<String, Object> profile = repo.getProfile(needyId);
        String docUrl = profile == null ? null : (String) profile.get("document");
        if (docUrl != null && !docUrl.isBlank()) {
            deleteQuietly(Paths.get(needyUploadDir, basename(docUrl)));
        }
        jdbc.update("UPDATE needy_profile SET document = NULL WHERE needy_id = ?", needyId);
        jdbc.update("UPDATE needy SET document = NULL WHERE id = ?", needyId);
    }

    private static MediaType mediaTypeFor(String filename) {
        String f = filename.toLowerCase();
        if (f.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (f.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (f.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }

    @PatchMapping("/needy/{needyId}/geo_push")
    public Map<String, Object> setGeoPush(@PathVariable int needyId, @RequestBody GeoPushUpdate payload,
                                          @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        boolean enabled = Boolean.TRUE.equals(payload.enabled());
        if (!repo.setGeoPushEnabled(needyId, enabled)) {
            throw new ApiException(404, "Needy not found");
        }
        return Map.of("geo_push_enabled", enabled);
    }

    @GetMapping("/needy/{needyId}/export")
    public ResponseEntity<Map<String, Object>> exportAccount(@PathVariable int needyId,
                                                             @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        Map<String, Object> data = repo.exportAccount(needyId);
        if (data == null) {
            throw new ApiException(404, "Needy not found");
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"savefood_data_" + needyId + ".json\"")
            .body(data);
    }

    @DeleteMapping("/needy/{needyId}/account")
    public Map<String, Object> deleteAccount(@PathVariable int needyId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        NeedyService.EraseResult result = service.eraseAccount(needyId);
        if (result == null) {
            throw new ApiException(404, "Needy not found");
        }
        // Delete the encrypted ID document (this module's upload dir).
        if (result.document() != null) {
            deleteQuietly(Paths.get(needyUploadDir, basename(result.document())));
        }
        // Delete any delivery photos (volunteer uploads dir).
        for (String photo : result.photos()) {
            if (photo != null && photo.startsWith("/volunteer_uploads/")) {
                deleteQuietly(Paths.get(volunteerUploadDir, basename(photo)));
            }
        }
        return Map.of("ok", true, "deleted", true);
    }

    // ── Notifications ────────────────────────────────────────────────────────────

    @GetMapping("/needy/{needyId}/notifications")
    public List<Map<String, Object>> getNotifications(@PathVariable int needyId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        if (repo.getNeedyById(needyId) == null) {
            throw new ApiException(404, "Needy not found");
        }
        return repo.getNotifications(needyId);
    }

    @PatchMapping("/needy/notifications/{notificationId}/read")
    public Map<String, Object> markNotificationRead(@PathVariable int notificationId,
                                                    @Auth CurrentUser user) {
        Map<String, Object> note = repo.getNotificationById(notificationId);
        if (note == null) {
            throw new ApiException(404, "Notification not found");
        }
        Object needyId = note.get("needy_id");
        Authz.ensureOwnerOrAdmin(user, "needy", needyId == null ? -1 : ((Number) needyId).intValue());
        repo.markNotificationRead(notificationId);
        return Map.of("ok", true);
    }

    // ── Public lots map ───────────────────────────────────────────────────────────

    @GetMapping("/lots")
    public List<Map<String, Object>> allActiveLots(@RequestParam(defaultValue = "20") int limit,
                                                   @RequestParam(defaultValue = "0") int offset,
                                                   @RequestParam(required = false) String category,
                                                   @RequestParam(required = false) String search) {
        // The hottest read on the platform (every recipient's map). Short-TTL
        // read-through cache (see CacheService); no write-side invalidation needed.
        String key = "lots:active:" + limit + ":" + offset + ":"
            + (category == null ? "" : category) + ":" + (search == null ? "" : search);
        return cache.cachedJson(key, CacheService.TTL_LOTS,
            () -> shopRepo.getAllActiveLots(limit, offset, category, search));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private Map<String, Object> upsertProfile(int needyId, NeedyProfileUpsert p, CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        Map<String, Object> profile = repo.createOrUpdateProfile(needyId, p.address(), p.familySize(),
            p.preferences(), p.urgency(), null, p.availableTime(), p.apartment(), p.floorNum(),
            p.entrance(), p.city(), p.lat(), p.lon());
        if (profile == null) {
            throw new ApiException(404, "Needy not found");
        }
        return profile;
    }

    /** Mirror pydantic NeedyCreate.password Field(min_length=8, max_length=128). */
    private static void validatePassword(String password) {
        if (password.length() < 8 || password.length() > 128) {
            throw new ApiException(422, "password: длина должна быть от 8 до 128 символов");
        }
    }

    private static String basename(String url) {
        return Paths.get(url).getFileName().toString();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort, like the Python os.remove in a failure branch
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
