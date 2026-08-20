package ru.savefood.needy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import ru.savefood.cache.CacheService;
import ru.savefood.needy.dto.GeoPushUpdate;
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
import ru.savefood.util.Clamp;
import ru.savefood.util.Geo;
import ru.savefood.util.Qr;
import ru.savefood.web.ApiException;
import ru.savefood.web.ClientIp;
import ru.savefood.web.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
 * profile, tickets, notifications, history, ratings, impact
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
    private final PhotoModerationService photoModeration;
    private final RateLimiter rateLimiter;
    private final TelegramService telegram;
    private final String deliveryPhotoUploadDir;
    /** Only used to remove legacy photos written before private storage existed. */
    private final String legacyVolunteerUploadDir;

    public NeedyController(NeedyRepository repo, NeedyService service, ShopRepository shopRepo,
                          CacheService cache, UploadService uploads,
                          PhotoModerationService photoModeration,
                          RateLimiter rateLimiter, TelegramService telegram,
                          @Value("${savefood.delivery-photo-upload-dir}") String deliveryPhotoUploadDir,
                          @Value("${savefood.volunteer-upload-dir}") String legacyVolunteerUploadDir) {
        this.repo = repo;
        this.service = service;
        this.shopRepo = shopRepo;
        this.cache = cache;
        this.uploads = uploads;
        this.photoModeration = photoModeration;
        this.rateLimiter = rateLimiter;
        this.telegram = telegram;
        this.deliveryPhotoUploadDir = deliveryPhotoUploadDir;
        this.legacyVolunteerUploadDir = legacyVolunteerUploadDir;
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
        if (!isUsableRecipientStatus(needy.get("status"))) {
            throw new ApiException(403, "Account is not active");
        }

        boolean selfPickup = Boolean.TRUE.equals(payload.selfPickup());
        Double lat = payload.lat();
        Double lon = payload.lon();
        String address = payload.address();
        String apartment = payload.apartment();
        String floorNum = payload.floorNum();
        String entrance = payload.entrance();

        // A self-pickup QR is redeemed at the shop, so retaining a home address
        // or home GPS coordinates serves no route purpose and leaks unnecessary
        // recipient PII through ticket reads. Ignore those direct-API fields.
        if (selfPickup) {
            lat = null;
            lon = null;
            address = null;
            apartment = null;
            floorNum = null;
            entrance = null;
        } else {
            validateProvidedCoordinates(lat, lon);
        }
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

        if (!selfPickup && !Geo.isValidCoordinates(lat, lon)) {
            throw new ApiException(422,
                "Для доставки укажите корректные координаты адреса (широта -90..90, долгота -180..180)");
        }
        if (!selfPickup && (address == null || address.isBlank())) {
            throw new ApiException(422, "Для доставки укажите адрес получателя");
        }

        try {
            int ticketId = service.createTicket(needyId, payload.items(), address, lat, lon,
                payload.availableTime(), payload.lotId(), apartment, floorNum,
                entrance, selfPickup);
            Map<String, Object> ticket = repo.getTicketById(ticketId);
            String qrCode = Qr.buildCode(ticketId, ticket == null ? null : (String) ticket.get("qr_secret"));
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("id", ticketId);
            out.put("qr_code", qrCode);
            return out;
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
        return repo.getHistory(needyId, Clamp.clamp(limit, 1, 100), Math.max(0, offset));
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
                    "× Получатель отменил заявку #" + ticketId + " — точка снята с вашего маршрута.");
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

        // Delivery photos are private until moderation; only the approved feed
        // endpoint may read them.  Never place a pending upload under nginx's
        // public /volunteer_uploads alias.
        String filename = uploads.validateAndSave(file, deliveryPhotoUploadDir);
        String photoRef = "/delivery_photos/" + filename;
        String oldPhoto;
        try {
            oldPhoto = service.setDeliveryPhotoPending(needyId, ticketId, photoRef);
        } catch (RuntimeException e) {
            deleteQuietly(Paths.get(deliveryPhotoUploadDir, filename));
            throw e;
        }

        // The DB now points at the new file — the replaced photo (if any) is an orphan.
        deleteDeliveryPhoto(oldPhoto);

        // Fire-and-forget AI "is this actually food?" pre-check (§36.1).
        photoModeration.startPhotoCheck(ticketId, Paths.get(deliveryPhotoUploadDir, filename).toString(), photoRef);
        return Map.of("photo_url", "/impact/delivery_photos/" + ticketId + "/image", "status", "pending");
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
        Map<String, Object> updated = service.updateNeedy(needyId, payload.name(), payload.contact());
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

    @PatchMapping("/needy/{needyId}/geo_push")
    public Map<String, Object> setGeoPush(@PathVariable int needyId, @RequestBody GeoPushUpdate payload,
                                          @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        boolean enabled = Boolean.TRUE.equals(payload.enabled());
        if (!service.setGeoPushEnabled(needyId, enabled)) {
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
        // Delete any delivery photos from private storage (and legacy public
        // storage for pre-migration rows).
        for (String photo : result.photos()) {
            deleteDeliveryPhoto(photo);
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
        int ownerId = needyId == null ? -1 : ((Number) needyId).intValue();
        Authz.ensureOwnerOrAdmin(user, "needy", ownerId);
        service.markNotificationRead(ownerId, notificationId);
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
        int lim = Clamp.clamp(limit, 1, 100);
        int off = Math.max(0, offset);
        String key = "lots:active:" + lim + ":" + off + ":"
            + (category == null ? "" : category) + ":" + (search == null ? "" : search);
        return cache.cachedJson(key, CacheService.TTL_LOTS,
            () -> shopRepo.getAllActiveLots(lim, off, category, search));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private Map<String, Object> upsertProfile(int needyId, NeedyProfileUpsert p, CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        Map<String, Object> current = repo.getProfile(needyId);
        // Coordinate clearing is destructive and must be explicit. Ordinary
        // profile saves often submit an unchanged address without re-sending the
        // geocoder result; implicitly erasing that location made the recipient
        // disappear from the volunteer map.
        boolean clearCoordinates = Boolean.TRUE.equals(p.clearCoordinates());
        if (clearCoordinates && (p.lat() != null || p.lon() != null)) {
            throw new ApiException(422, "clear_coordinates нельзя сочетать с lat/lon");
        }
        validateProvidedCoordinates(p.lat(), p.lon());
        if (!clearCoordinates && (p.lat() != null || p.lon() != null)) {
            Double finalLat = p.lat() != null ? p.lat() : asDouble(current == null ? null : current.get("lat"));
            Double finalLon = p.lon() != null ? p.lon() : asDouble(current == null ? null : current.get("lon"));
            if (!Geo.isValidCoordinates(finalLat, finalLon)) {
                throw new ApiException(422,
                    "lat/lon: укажите обе координаты в диапазонах -90..90 и -180..180");
            }
        }
        Map<String, Object> profile = service.createOrUpdateProfile(needyId, p.address(), p.familySize(),
            p.preferences(), p.urgency(), p.availableTime(), p.apartment(), p.floorNum(),
            p.entrance(), p.city(), p.lat(), p.lon(), clearCoordinates);
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

    static boolean isUsableRecipientStatus(Object status) {
        return "active".equals(status) || "pending".equals(status)
            || "approved".equals(status) || "rejected".equals(status);
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private void deleteDeliveryPhoto(String photo) {
        if (photo == null || photo.isBlank()) {
            return;
        }
        if (photo.startsWith("/delivery_photos/")) {
            deleteQuietly(Paths.get(deliveryPhotoUploadDir, basename(photo)));
        } else if (photo.startsWith("/volunteer_uploads/")) {
            deleteQuietly(Paths.get(legacyVolunteerUploadDir, basename(photo)));
        }
    }

    private static void validateProvidedCoordinates(Double lat, Double lon) {
        if (lat != null && !Geo.isValidLatitude(lat)) {
            throw new ApiException(422, "lat: значение должно быть конечным и в диапазоне -90..90");
        }
        if (lon != null && !Geo.isValidLongitude(lon)) {
            throw new ApiException(422, "lon: значение должно быть конечным и в диапазоне -180..180");
        }
    }
}
