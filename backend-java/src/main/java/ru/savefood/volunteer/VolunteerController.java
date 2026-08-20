package ru.savefood.volunteer;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import ru.savefood.audit.AuditService;
import ru.savefood.kyc.KycCrypto;
import ru.savefood.kyc.KycService;
import ru.savefood.volunteer.dto.ModerationUpdate;
import ru.savefood.photo.PhotoModerationService;
import ru.savefood.security.Auth;
import ru.savefood.security.Authz;
import ru.savefood.security.CurrentUser;
import ru.savefood.telegram.TelegramService;
import ru.savefood.upload.UploadService;
import ru.savefood.util.Geo;
import ru.savefood.util.Html;
import ru.savefood.volunteer.dto.AvailabilityWindow;
import ru.savefood.volunteer.dto.CompletePointRequest;
import ru.savefood.volunteer.dto.FinishRouteRequest;
import ru.savefood.volunteer.dto.LocationUpdate;
import ru.savefood.volunteer.dto.StartRouteRequest;
import ru.savefood.volunteer.dto.TeamCreate;
import ru.savefood.volunteer.dto.TeamJoin;
import ru.savefood.volunteer.dto.VolunteerCreate;
import ru.savefood.volunteer.dto.VolunteerUpdate;
import ru.savefood.web.ApiException;
import ru.savefood.web.ClientIp;
import ru.savefood.web.RateLimiter;
import ru.savefood.webhook.WebhookService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Java port of backend/volunteer/routes.py — the volunteer/courier surface
 * (registration, identity KYC upload, the live map, route lifecycle with
 * server-side QR+GPS delivery verification, ratings, gamified stats, teams and
 * location tracking). Authenticated routes take an {@code @Auth CurrentUser} and
 * call {@link Authz#ensureOwnerOrAdmin}; {@code POST /volunteers/register} is
 * public and rate-limited. Transactional flows live in {@link VolunteerService}.
 */
@RestController
public class VolunteerController {

    private static final Pattern HM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private final VolunteerRepository repo;
    private final VolunteerService service;
    private final RateLimiter rateLimiter;
    private final UploadService uploads;
    private final KycCrypto kycCrypto;
    private final KycService kycService;
    private final PhotoModerationService photoModeration;
    private final WebhookService webhooks;
    private final TelegramService telegram;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final boolean kycRequired;
    private final String kycUploadDir;
    private final String deliveryPhotoUploadDir;

    public VolunteerController(VolunteerRepository repo, VolunteerService service, RateLimiter rateLimiter,
                              UploadService uploads, KycCrypto kycCrypto, KycService kycService,
                              PhotoModerationService photoModeration,
                              WebhookService webhooks, TelegramService telegram,
                              JdbcTemplate jdbc, AuditService audit,
                              @Value("${savefood.volunteer-kyc-required:true}") boolean kycRequired,
                              @Value("${savefood.volunteer-kyc-upload-dir:../backend/volunteer/kyc_uploads}")
                                  String kycUploadDir,
                              @Value("${savefood.delivery-photo-upload-dir:../backend/volunteer/delivery_photos}")
                                  String deliveryPhotoUploadDir) {
        this.repo = repo;
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.uploads = uploads;
        this.kycCrypto = kycCrypto;
        this.kycService = kycService;
        this.photoModeration = photoModeration;
        this.webhooks = webhooks;
        this.telegram = telegram;
        this.jdbc = jdbc;
        this.audit = audit;
        this.kycRequired = kycRequired;
        this.kycUploadDir = kycUploadDir;
        this.deliveryPhotoUploadDir = deliveryPhotoUploadDir;
    }

    // ── Registration / KYC ────────────────────────────────────────────────────────

    @PostMapping("/volunteers/register")
    public Map<String, Object> register(@RequestBody VolunteerCreate vol, HttpServletRequest request) {
        rateLimiter.check("volunteers:register", ClientIp.of(request), 5);
        if (isBlank(vol.username()) || isBlank(vol.password())) {
            throw new ApiException(400, "Укажите логин и пароль");
        }
        validatePassword(vol.password());
        validateOptionalCoordinates(vol.lat(), vol.lon());
        return Map.of("id", service.registerVolunteer(vol));
    }

    @PostMapping("/volunteers/{volunteerId}/document/upload")
    public Map<String, Object> uploadDocument(@PathVariable int volunteerId,
                                              @RequestParam(required = false) MultipartFile file,
                                              @Auth CurrentUser user, HttpServletRequest request) {
        rateLimiter.check("volunteers:document", ClientIp.of(request), 10);
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        Map<String, Object> vol = repo.getVolunteerById(volunteerId);
        if (vol == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        String filename = uploads.validateAndSave(file, kycUploadDir, true);
        Path path = Paths.get(kycUploadDir, filename);
        String document = "/volunteer_kyc/" + filename;
        String generation = UUID.randomUUID().toString();
        VolunteerRepository.KycDocumentReplacement replacement;
        // Encrypt the identity document at rest immediately (§58): on disk only ciphertext.
        try {
            kycCrypto.encryptFile(path.toString());
            replacement = repo.replaceVolunteerKycDocument(volunteerId, document, generation);
            if (replacement == null) {
                throw new ApiException(404, "Volunteer not found");
            }
        } catch (RuntimeException e) {
            deleteQuietly(path);
            throw e;
        }
        if (replacement.previousDocument() != null
                && !replacement.previousDocument().equals(document)) {
            deleteQuietly(Paths.get(kycUploadDir, basename(replacement.previousDocument())));
        }
        String name = vol.get("name") == null ? "" : vol.get("name").toString();
        kycService.startVolunteerKycCheck(volunteerId, path.toString(), name, generation);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("status", "pending");
        return out;
    }

    /**
     * Serve the volunteer's identity document, decrypted in memory (§58). The
     * volunteer owner only; moderators use the AI verdict, score, and notes.
     */
    @GetMapping("/volunteers/{volunteerId}/document")
    public ResponseEntity<byte[]> getDocument(@PathVariable int volunteerId, @Auth CurrentUser user) {
        Authz.ensureOwner(user, "volunteer", volunteerId);
        Map<String, Object> vol = repo.getVolunteerById(volunteerId);
        String docUrl = vol == null ? null : (String) vol.get("document");
        if (docUrl == null || docUrl.isBlank()) {
            throw new ApiException(404, "Документ не найден");
        }
        Path path = Paths.get(kycUploadDir, basename(docUrl));
        if (!Files.isRegularFile(path)) {
            throw new ApiException(404, "Документ не найден");
        }
        byte[] content;
        try {
            content = kycCrypto.readDecrypted(path.toString());
        } catch (Exception e) {
            throw new ApiException(500, "Не удалось прочитать документ");
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(mediaTypeFor(basename(docUrl)))
            .body(content);
    }

    /**
     * Moderator decision on a pending volunteer (hybrid KYC, §58): approve or reject.
     * On a decision the identity document is deleted from disk (§5 — PII).
     */
    @PatchMapping("/volunteers/{volunteerId}/moderation")
    public Map<String, Object> moderate(@PathVariable int volunteerId,
            @RequestBody ModerationUpdate payload, @Auth CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(403, "Только администратор");
        }
        String status = payload.status();
        if (!"approved".equals(status) && !"rejected".equals(status)) {
            throw new ApiException(400, "status must be 'approved' or 'rejected'");
        }
        VolunteerRepository.KycModerationTransition transition =
            repo.moderateVolunteerKyc(volunteerId, status);
        if (transition == null) {
            throw new ApiException(409, "Волонтёр уже промодерирован или не найден");
        }
        deleteDocument(volunteerId, transition.document(), transition.generation());
        boolean approved = "approved".equals(status);
        String msg = approved
            ? "Ваш аккаунт волонтёра подтверждён модератором — можно брать маршруты."
            : "Удостоверение отклонено. Загрузите корректный документ, удостоверяющий личность.";
        jdbc.update(
            "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
            volunteerId, approved ? "moderation_approved" : "moderation_rejected", msg, OffsetDateTime.now());
        try {
            telegram.notifyVolunteer(volunteerId, (approved ? "✓ " : "! ") + msg);
        } catch (RuntimeException ignore) {
            // best-effort
        }
        audit.log(user.sub(), "volunteer_moderation", "volunteer", volunteerId,
            "Admin set volunteer #" + volunteerId + " to " + status);
        return Map.of("ok", true, "status", status);
    }

    /** Re-run the AI verdict synchronously while the document exists (§38.2). Admin only. */
    @PostMapping("/volunteers/{volunteerId}/kyc_recheck")
    public Map<String, Object> recheck(@PathVariable int volunteerId, @Auth CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(403, "Только администратор");
        }
        Map<String, Object> vol = repo.getVolunteerById(volunteerId);
        String docUrl = vol == null ? null : (String) vol.get("document");
        String generation = vol == null ? null : (String) vol.get("kyc_generation");
        if (docUrl == null || docUrl.isBlank()) {
            throw new ApiException(404, "Документ уже удалён — перепроверка недоступна");
        }
        if (generation == null || generation.isBlank()) {
            throw new ApiException(409, "Документ не имеет поколения KYC");
        }
        String name = vol.get("name") == null ? "" : vol.get("name").toString();
        kycService.recheckVolunteer(volunteerId,
            Paths.get(kycUploadDir, basename(docUrl)).toString(), name, generation);
        audit.log(user.sub(), "kyc_recheck", "volunteer", volunteerId,
            "Admin re-ran AI KYC for volunteer #" + volunteerId);
        Map<String, Object> updated = repo.getVolunteerById(volunteerId);
        if (updated == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        return Map.of(
            "kyc_verdict", updated.get("kyc_verdict") == null ? "unchecked" : updated.get("kyc_verdict"),
            "kyc_score", updated.get("kyc_score") == null ? "" : updated.get("kyc_score"),
            "kyc_notes", updated.get("kyc_notes") == null ? "" : updated.get("kyc_notes"));
    }

    /** Delete the volunteer's identity document from disk and clear the column (§5). */
    private void deleteDocument(int volunteerId, String docUrl, String generation) {
        if (docUrl != null && !docUrl.isBlank() && generation != null
                && repo.clearVolunteerKycDocument(volunteerId, docUrl, generation)) {
            try {
                Files.deleteIfExists(Paths.get(kycUploadDir, basename(docUrl)));
            } catch (Exception ignore) {
                // best-effort
            }
        }
    }

    private static String basename(String url) {
        return Paths.get(url).getFileName().toString();
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

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Best effort after a database replacement/validation failure.
        }
    }

    // ── Map / profile ─────────────────────────────────────────────────────────────

    @GetMapping("/volunteers/map")
    public Map<String, Object> getMap(@Auth CurrentUser user) {
        if (!user.isAdmin() && !"volunteer".equals(user.role())) {
            throw new ApiException(403, "Forbidden");
        }
        // Map pins include where vulnerable recipients live (albeit coarsened),
        // so a pending/rejected volunteer must not be able to enumerate them.
        if (!user.isAdmin()) {
            if (user.relatedId() == null) {
                throw new ApiException(403, "Forbidden");
            }
            Map<String, Object> volunteer = repo.getVolunteerById(user.relatedId());
            if (volunteer == null || !"approved".equals(volunteer.get("status"))) {
                throw new ApiException(403,
                    "Карта заявок доступна после подтверждения аккаунта волонтёра");
            }
        }
        return service.mapPoints();
    }

    @GetMapping("/volunteers/{volunteerId}")
    public Map<String, Object> getVolunteer(@PathVariable int volunteerId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        Map<String, Object> v = repo.getVolunteerById(volunteerId);
        if (v == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        return volunteerOut(v);
    }

    @PatchMapping("/volunteers/{volunteerId}")
    public Map<String, Object> patchVolunteer(@PathVariable int volunteerId,
                                              @RequestBody VolunteerUpdate payload, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        Map<String, Object> current = repo.getVolunteerById(volunteerId);
        if (current == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        Double finalLat = payload.lat() != null ? payload.lat() : asDouble(current.get("lat"));
        Double finalLon = payload.lon() != null ? payload.lon() : asDouble(current.get("lon"));
        validateOptionalCoordinates(finalLat, finalLon);
        if (payload.capacityKg() != null
                && (!Double.isFinite(payload.capacityKg()) || payload.capacityKg() < 0)) {
            throw new ApiException(422, "capacity_kg: значение должно быть конечным и неотрицательным");
        }
        String availabilityJson = null;
        if (payload.availability() != null) {
            availabilityJson = availabilityJson(payload.availability());
        }
        Map<String, Object> updated = repo.updateVolunteer(volunteerId, payload.name(), payload.contact(),
            payload.lat(), payload.lon(), payload.city(), payload.hasThermalBag(), payload.capacityKg(),
            availabilityJson);
        if (updated == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        return updated;
    }

    // ── Route lifecycle ───────────────────────────────────────────────────────────

    @PostMapping("/volunteers/{volunteerId}/start_route")
    public Map<String, Object> startRoute(@PathVariable int volunteerId,
                                          @RequestBody StartRouteRequest payload, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        Map<String, Object> vol = repo.getVolunteerById(volunteerId);
        if (vol == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        enforceVolunteerKyc(vol, user);
        if (repo.getActiveRoute(volunteerId) != null) {
            throw new ApiException(400, "Volunteer already has an active route");
        }
        if (payload.lotId() == null) {
            throw new ApiException(422, "lot_id: обязательное поле");
        }
        if (payload.maxStops() != null && payload.maxStops() < 1) {
            throw new ApiException(422, "max_stops: значение должно быть ≥ 1");
        }

        VolunteerService.StartRouteResult result =
            service.startRoute(volunteerId, vol, payload.lotId(), payload.maxStops());

        // Enterprise webhook (ERP integration) — fire-and-forget, outside the tx.
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("lot_id", payload.lotId());
            data.put("route_id", result.routeId());
            data.put("description", result.lotDescription());
            data.put("quantity", result.lotQuantity());
            data.put("volunteer_name", result.volName());
            webhooks.fire(result.shopId(), "lot.taken", data);
        } catch (RuntimeException e) {
            // best-effort, like the Python try/except around webhook_service.fire
        }

        // Telegram fan-out after the transaction, like Python's post-response
        // BackgroundTasks. Names and lot descriptions originate from user input,
        // so escape them before embedding into an HTML-parsed Telegram message.
        String safeVolName = Html.escape(result.volName());
        String lotDesc = result.lotDescription() == null || result.lotDescription().isEmpty()
            ? "лот #" + payload.lotId() : result.lotDescription();
        try {
            telegram.notifyShop(result.shopId(), "□ Волонтёр <b>" + safeVolName + "</b> взял ваш лот «"
                + Html.escape(lotDesc) + "». Маршрут #" + result.routeId() + " в пути.");
        } catch (RuntimeException ignore) {
            // best-effort
        }
        for (int[] pair : result.assignedNeedy()) {
            try {
                telegram.notifyNeedy(pair[0], "→ Волонтёр <b>" + safeVolName + "</b> принял вашу заявку #"
                    + pair[1] + " и скоро поедет в магазин.");
            } catch (RuntimeException ignore) {
                // best-effort
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route_id", result.routeId());
        List<Map<String, Object>> responsePoints = result.points().stream()
            .map(LinkedHashMap::new).toList();
        if (!("volunteer".equals(user.role()) && Objects.equals(user.relatedId(), volunteerId))) {
            RoutePointPrivacy.redactAllTicketPoints(responsePoints);
        }
        out.put("points", responsePoints);
        return out;
    }

    @PostMapping("/volunteers/route/{routeId}/complete_point")
    public Map<String, Object> completePoint(@PathVariable int routeId,
                                             @RequestBody CompletePointRequest payload, @Auth CurrentUser user) {
        Map<String, Object> route = requireRouteOwner(routeId, user, true);
        service.completePoint(route, ((Number) route.get("volunteer_id")).intValue(),
            payload.ticketId(), payload.lat(), payload.lon(), payload.qrCode());
        return Map.of("ok", true);
    }

    @PostMapping("/volunteers/route/{routeId}/finish")
    public Map<String, Object> finishRoute(@PathVariable int routeId,
                                           @RequestBody(required = false) FinishRouteRequest payload,
                                           @Auth CurrentUser user) {
        Map<String, Object> route = requireRouteOwner(routeId, user, true);
        service.finishRoute(route);
        return Map.of("ok", true);
    }

    @PostMapping("/volunteers/route/{routeId}/attempt_delivery")
    public Map<String, Object> attemptDelivery(@PathVariable int routeId,
                                               @RequestBody CompletePointRequest payload, @Auth CurrentUser user) {
        Map<String, Object> route = requireRouteOwner(routeId, user, true);
        return service.attemptDelivery(route, ((Number) route.get("volunteer_id")).intValue(),
            payload.ticketId(), payload.lat(), payload.lon());
    }

    /**
     * Store a courier's proof photo for an active assigned stop. Pending photos
     * live outside public nginx aliases; only moderation-approved files can be
     * read through the public impact endpoint.
     */
    @PostMapping(value = "/volunteers/route/{routeId}/ticket/{ticketId}/photo",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadDeliveryPhoto(@PathVariable int routeId, @PathVariable int ticketId,
                                                    @RequestParam MultipartFile file,
                                                    @RequestParam Double lat, @RequestParam Double lon,
                                                    @Auth CurrentUser user, HttpServletRequest request) {
        rateLimiter.checkHourly("volunteers:delivery_photo", ClientIp.of(request), 10);
        Map<String, Object> route = requireRouteOwner(routeId, user, true);
        int volunteerId = ((Number) route.get("volunteer_id")).intValue();
        String filename = uploads.validateAndSave(file, deliveryPhotoUploadDir);
        String photoRef = "/delivery_photos/" + filename;
        String oldPhoto;
        try {
            oldPhoto = service.attachDeliveryPhoto(route, volunteerId, ticketId, lat, lon, photoRef);
        } catch (RuntimeException e) {
            deleteQuietly(Paths.get(deliveryPhotoUploadDir, filename));
            throw e;
        }
        if (oldPhoto != null && oldPhoto.startsWith("/delivery_photos/")) {
            deleteQuietly(Paths.get(deliveryPhotoUploadDir, basename(oldPhoto)));
        }
        photoModeration.startPhotoCheck(ticketId, Paths.get(deliveryPhotoUploadDir, filename).toString(), photoRef);
        return Map.of("ok", true, "status", "pending",
            "photo_url", "/impact/delivery_photos/" + ticketId + "/image");
    }

    @GetMapping("/volunteers/{volunteerId}/history")
    public List<Map<String, Object>> history(@PathVariable int volunteerId,
                                             @RequestParam(defaultValue = "20") int limit,
                                             @RequestParam(defaultValue = "0") int offset,
                                             @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        return service.historyRoutes(volunteerId, Math.min(Math.max(limit, 1), 100), Math.max(0, offset));
    }

    @GetMapping("/volunteers/{volunteerId}/active_route")
    public Map<String, Object> activeRoute(@PathVariable int volunteerId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        boolean assignedVolunteer = "volunteer".equals(user.role())
            && Objects.equals(user.relatedId(), volunteerId);
        return service.activeRoute(volunteerId, assignedVolunteer);
    }

    // ── Notifications / rating / stats / thanks ──────────────────────────────────

    @GetMapping("/volunteers/{volunteerId}/notifications")
    public List<Map<String, Object>> notifications(@PathVariable int volunteerId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        if (repo.getVolunteerById(volunteerId) == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        return repo.getNotifications(volunteerId);
    }

    @PatchMapping("/volunteers/notifications/{notificationId}/read")
    public Map<String, Object> markNotificationRead(@PathVariable int notificationId, @Auth CurrentUser user) {
        Map<String, Object> note = repo.getNotificationById(notificationId);
        if (note == null) {
            throw new ApiException(404, "Notification not found");
        }
        Integer ownerId = note.get("volunteer_id") == null ? null : ((Number) note.get("volunteer_id")).intValue();
        if (!user.isAdmin()
                && !("volunteer".equals(user.role()) && ownerId != null && ownerId.equals(user.relatedId()))) {
            throw new ApiException(403, "Forbidden");
        }
        repo.markNotificationRead(notificationId);
        return Map.of("ok", true);
    }

    @GetMapping("/volunteers/{volunteerId}/rating")
    public Map<String, Object> rating(@PathVariable int volunteerId, @Auth CurrentUser user) {
        // Any authenticated user may read a volunteer's rating (no ownership check, like Python).
        return service.ratingSummary(volunteerId);
    }

    @GetMapping("/volunteers/{volunteerId}/stats")
    public Map<String, Object> stats(@PathVariable int volunteerId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        return service.stats(volunteerId);
    }

    @GetMapping("/volunteers/{volunteerId}/thanks")
    public List<Map<String, Object>> thanks(@PathVariable int volunteerId,
                                            @RequestParam(defaultValue = "30") int limit,
                                            @RequestParam(defaultValue = "0") int offset,
                                            @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        return service.thanks(volunteerId, limit, offset);
    }

    // ── Teams ──────────────────────────────────────────────────────────────────────

    @GetMapping("/volunteers/{volunteerId}/team")
    public Map<String, Object> getTeam(@PathVariable int volunteerId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        Map<String, Object> vol = repo.getVolunteerById(volunteerId);
        if (vol == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        if (vol.get("team_id") == null) {
            return nullableTeam(null);
        }
        return nullableTeam(service.teamSummary(((Number) vol.get("team_id")).intValue()));
    }

    @PostMapping("/volunteers/{volunteerId}/team/create")
    public Map<String, Object> createTeam(@PathVariable int volunteerId,
                                          @RequestBody TeamCreate payload, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        Map<String, Object> vol = repo.getVolunteerById(volunteerId);
        if (vol == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        if (vol.get("team_id") != null) {
            throw new ApiException(400, "Вы уже состоите в команде — сначала покиньте её");
        }
        String name = payload.name() == null ? "" : payload.name().strip();
        if (name.length() < 3) {
            throw new ApiException(400, "Название команды — минимум 3 символа");
        }
        return nullableTeam(service.createTeam(volunteerId, name));
    }

    @PostMapping("/volunteers/{volunteerId}/team/join")
    public Map<String, Object> joinTeam(@PathVariable int volunteerId,
                                        @RequestBody TeamJoin payload, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        Map<String, Object> vol = repo.getVolunteerById(volunteerId);
        if (vol == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        if (vol.get("team_id") != null) {
            throw new ApiException(400, "Вы уже состоите в команде — сначала покиньте её");
        }
        String code = payload.code() == null ? "" : payload.code().strip().toUpperCase();
        return nullableTeam(service.joinTeam(volunteerId, code));
    }

    @PostMapping("/volunteers/{volunteerId}/team/leave")
    public Map<String, Object> leaveTeam(@PathVariable int volunteerId, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        service.leaveTeam(volunteerId);
        return Map.of("ok", true);
    }

    // ── Location ─────────────────────────────────────────────────────────────────

    @PatchMapping("/volunteers/{volunteerId}/location")
    public Map<String, Object> updateLocation(@PathVariable int volunteerId,
                                              @RequestBody LocationUpdate payload, @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "volunteer", volunteerId);
        if (!Geo.isValidCoordinates(payload.lat(), payload.lon())) {
            throw new ApiException(422, "lat/lon: укажите координаты в диапазонах -90..90 и -180..180");
        }
        // Postgres is the source of truth; the Redis write-through cache stays on
        // the Python layer during the migration (CacheService is a no-op here).
        repo.updateVolunteerLocation(volunteerId, payload.lat(), payload.lon());
        return Map.of("ok", true);
    }

    @GetMapping("/volunteers/{volunteerId}/location")
    public Map<String, Object> getLocation(@PathVariable int volunteerId, @Auth CurrentUser user) {
        boolean allowed = user.isAdmin()
            || ("volunteer".equals(user.role()) && Objects.equals(user.relatedId(), volunteerId))
            || ("needy".equals(user.role()) && user.relatedId() != null
                && repo.needyHasVolunteer(user.relatedId(), volunteerId));
        if (!allowed) {
            throw new ApiException(403, "Forbidden");
        }
        Map<String, Object> loc = repo.getVolunteerLocation(volunteerId);
        if (loc == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        return loc;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void enforceVolunteerKyc(Map<String, Object> vol, CurrentUser user) {
        if (kycRequired && !user.isAdmin() && !"approved".equals(vol.get("status"))) {
            throw new ApiException(403,
                "Аккаунт волонтёра ещё не верифицирован — загрузите удостоверение личности и дождитесь "
                + "проверки, чтобы брать маршруты");
        }
    }

    private Map<String, Object> requireRouteOwner(int routeId, CurrentUser user, boolean requireActive) {
        Map<String, Object> route = repo.getRouteById(routeId);
        if (route == null) {
            throw new ApiException(404, "Route not found");
        }
        Authz.ensureOwnerOrAdmin(user, "volunteer", ((Number) route.get("volunteer_id")).intValue());
        if (requireActive && !"in_progress".equals(route.get("status"))) {
            throw new ApiException(400, "Маршрут уже завершён или сброшен");
        }
        return route;
    }

    /** Validate the weekly windows (pydantic Field rules) and emit the stored JSON text. */
    private String availabilityJson(List<AvailabilityWindow> windows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < windows.size(); i++) {
            AvailabilityWindow w = windows.get(i);
            if (w.day() == null || w.day() < 0 || w.day() > 6) {
                throw new ApiException(422, "availability: day должен быть от 0 до 6");
            }
            if (w.start() == null || !HM.matcher(w.start()).matches()
                    || w.end() == null || !HM.matcher(w.end()).matches()) {
                throw new ApiException(422, "availability: start/end должны быть в формате HH:MM");
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"day\":").append(w.day())
              .append(",\"start\":\"").append(w.start())
              .append("\",\"end\":\"").append(w.end()).append("\"}");
        }
        return sb.append("]").toString();
    }

    /** Project the full volunteer row to the {@code VolunteerOut} field set. */
    private Map<String, Object> volunteerOut(Map<String, Object> v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", v.get("id"));
        out.put("name", v.get("name"));
        out.put("contact", v.get("contact"));
        out.put("lat", v.get("lat"));
        out.put("lon", v.get("lon"));
        out.put("city", v.get("city"));
        out.put("has_thermal_bag", v.get("has_thermal_bag") != null ? v.get("has_thermal_bag") : false);
        out.put("availability", v.get("availability"));
        out.put("status", v.get("status") != null ? v.get("status") : "approved");
        out.put("kyc_score", v.get("kyc_score"));
        out.put("kyc_verdict", v.get("kyc_verdict"));
        out.put("kyc_notes", v.get("kyc_notes"));
        out.put("created_at", v.get("created_at"));
        return out;
    }

    private static Map<String, Object> nullableTeam(Map<String, Object> team) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("team", team);
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Mirror pydantic VolunteerCreate.password Field(min_length=8, max_length=128). */
    private static void validatePassword(String password) {
        if (password.length() < 8 || password.length() > 128) {
            throw new ApiException(422, "password: длина должна быть от 8 до 128 символов");
        }
    }

    private static void validateOptionalCoordinates(Double lat, Double lon) {
        if (lat == null && lon == null) {
            return;
        }
        if (!Geo.isValidCoordinates(lat, lon)) {
            throw new ApiException(422, "lat/lon: укажите координаты в диапазонах -90..90 и -180..180");
        }
    }

    private static Double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
