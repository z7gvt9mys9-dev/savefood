package ru.savefood.admin;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.savefood.audit.AuditService;
import ru.savefood.billing.Plans;
import ru.savefood.esg.EsgService;
import ru.savefood.security.Admin;
import ru.savefood.security.CurrentUser;
import ru.savefood.photo.DeliveryPhotoStorage;
import ru.savefood.telegram.TelegramService;
import ru.savefood.util.Clamp;
import ru.savefood.volunteer.AvailabilityService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.RoutePointPrivacy;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final VolunteerRepository volunteerRepo;
    private final EsgService esgService;
    private final AuditService audit;
    private final RouteRevertService routeRevert;
    private final AvailabilityService availability;
    private final TelegramService telegram;
    private final DeliveryPhotoStorage deliveryPhotos;
    /** Legacy public dir is read only to clean up pre-private-storage rows. */
    private final String volunteerUploadDir;
    private final String deliveryPhotoUploadDir;
    @Autowired
    public AdminController(JdbcTemplate jdbc, VolunteerRepository volunteerRepo, EsgService esgService,
                           AuditService audit, RouteRevertService routeRevert,
                           AvailabilityService availability, TelegramService telegram,
                           DeliveryPhotoStorage deliveryPhotos,
                           @Value("${savefood.volunteer-upload-dir}") String volunteerUploadDir,
                           @Value("${savefood.delivery-photo-upload-dir}") String deliveryPhotoUploadDir) {
        this.jdbc = jdbc;
        this.volunteerRepo = volunteerRepo;
        this.esgService = esgService;
        this.audit = audit;
        this.routeRevert = routeRevert;
        this.availability = availability;
        this.telegram = telegram;
        this.deliveryPhotos = deliveryPhotos;
        this.volunteerUploadDir = volunteerUploadDir;
        this.deliveryPhotoUploadDir = deliveryPhotoUploadDir;
    }
    /** Constructor retained for focused controller tests without file cleanup. */
    public AdminController(JdbcTemplate jdbc, VolunteerRepository volunteerRepo, EsgService esgService,
                           AuditService audit, RouteRevertService routeRevert,
                           AvailabilityService availability, TelegramService telegram,
                           String volunteerUploadDir, String deliveryPhotoUploadDir) {
        this(jdbc, volunteerRepo, esgService, audit, routeRevert, availability, telegram,
            null, volunteerUploadDir, deliveryPhotoUploadDir);
    }
    /** Manual identity-document moderation for volunteers only. */
    @PatchMapping("/volunteers/{volunteerId}/moderation")
    public Map<String, Object> moderateVolunteer(@PathVariable int volunteerId,
                                                 @RequestBody ModerationDecision payload,
                                                 @Admin CurrentUser user) {
        String status = requireDecision(payload);
        Map<String, Object> updated = volunteerRepo.setVolunteerStatus(volunteerId, status, null);
        if (updated == null) {
            throw new ApiException(404, "Volunteer not found");
        }
        audit.log(user.sub(), "kyc_manual_" + status, "volunteer", volunteerId,
            "Ручное решение модератора: " + status + reasonSuffix(payload));
        notifyVolunteerModerationOutcome(volunteerId, status,
            "Ваш аккаунт волонтёра подтверждён модератором — можно брать маршруты.",
            "Удостоверение не принято модератором. Загрузите корректный документ, "
            + "удостоверяющий личность, чтобы брать маршруты.");
        return updated;
    }
    /** Identity-document moderation queue for volunteers (§58). */
    @GetMapping("/volunteers")
    public List<Map<String, Object>> listVolunteers(@RequestParam(required = false) String status,
                                                    @Admin CurrentUser user) {
        String columns = "id, name, contact, city, status, kyc_score, kyc_verdict, kyc_notes, "
            + "kyc_checked_at, created_at, (document IS NOT NULL) AS has_document";
        if (status != null && !status.isBlank()) {
            return jdbc.queryForList(
                "SELECT " + columns + " FROM volunteers WHERE status = ? ORDER BY created_at DESC",
                status);
        }
        return jdbc.queryForList("SELECT " + columns + " FROM volunteers ORDER BY created_at DESC");
    }
    private static String requireDecision(ModerationDecision payload) {
        String status = payload == null || payload.status() == null ? "" : payload.status().strip();
        if (!"approved".equals(status) && !"rejected".equals(status)) {
            throw new ApiException(422, "status должен быть 'approved' или 'rejected'");
        }
        return status;
    }
    private static String reasonSuffix(ModerationDecision payload) {
        String reason = payload == null ? null : payload.reason();
        return reason == null || reason.isBlank() ? "" : " — " + reason.strip();
    }
    private void notifyVolunteerModerationOutcome(int volunteerId, String status,
                                                  String approvedMsg, String rejectedMsg) {
        boolean approved = "approved".equals(status);
        String message = approved ? approvedMsg : rejectedMsg;
        jdbc.update(
            "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) "
            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0)",
            volunteerId, approved ? "moderation_approved" : "moderation_rejected", message);
        try {
            String prefixed = (approved ? "✓ " : "! ") + message;
            telegram.notifyVolunteer(volunteerId, prefixed);
        } catch (RuntimeException ignore) {
        }
    }
    @GetMapping("/delivery_photos")
    public List<Map<String, Object>> listDeliveryPhotos(
            @RequestParam(defaultValue = "pending") String status, @Admin CurrentUser user) {
        if (!Set.of("pending", "approved", "rejected").contains(status)) {
            throw new ApiException(400, "Invalid status");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT t.id AS ticket_id, t.delivery_photo, t.delivery_photo_status, "
            + "t.delivery_photo_ai_verdict, t.delivery_photo_ai_score, "
            + "t.delivery_photo_ai_notes, t.fulfilled_at, l.category, l.city "
            + "FROM tickets t LEFT JOIN lots l ON l.id = t.lot_id "
            + "WHERE t.delivery_photo IS NOT NULL AND t.delivery_photo_status = ? "
            + "ORDER BY t.fulfilled_at DESC NULLS LAST LIMIT 100",
            status);
        for (Map<String, Object> row : rows) {
            row.put("photo_ref", row.get("delivery_photo"));
            row.remove("delivery_photo");
            row.put("photo_url", "/admin/delivery_photos/" + row.get("ticket_id") + "/image");
        }
        return rows;
    }
    /** Pending proof images are visible to an authenticated moderator only. */
    @GetMapping("/delivery_photos/{ticketId}/image")
    public ResponseEntity<Resource> deliveryPhotoImage(@PathVariable int ticketId, @Admin CurrentUser user) {
        List<String> refs = jdbc.query("SELECT delivery_photo FROM tickets WHERE id = ? AND delivery_photo IS NOT NULL",
            (rs, n) -> rs.getString("delivery_photo"), ticketId);
        if (refs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path path = deliveryPhotoPath(refs.get(0));
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(mediaTypeFor(path.getFileName().toString()))
            .body(new FileSystemResource(path));
    }
    @PostMapping("/delivery_photos/{ticketId}/approve")
    public Map<String, Object> approveDeliveryPhoto(@PathVariable int ticketId,
                                                     @RequestParam("photo_ref") String photoRef,
                                                     @Admin CurrentUser user) {
        if (photoRef == null || photoRef.isBlank()) {
            throw new ApiException(422, "photo_ref обязателен");
        }
        int rows = jdbc.update(
            "UPDATE tickets SET delivery_photo_status = 'approved', "
            + "delivery_photo_reviewed_at = NOW() "
            + "WHERE id = ? AND delivery_photo = ? AND delivery_photo_status = 'pending'",
            ticketId, photoRef);
        if (rows == 0) {
            throw new ApiException(409,
                "Фото уже изменилось или обработано — обновите очередь перед решением");
        }
        audit.log(user.sub(), "photo_approve", "ticket", ticketId,
            "Admin approved delivery photo for ticket #" + ticketId);
        return Map.of("ok", true, "status", "approved");
    }
    @PostMapping("/delivery_photos/{ticketId}/reject")
    @Transactional
    public Map<String, Object> rejectDeliveryPhoto(@PathVariable int ticketId,
                                                    @RequestParam("photo_ref") String photoRef,
                                                    @Admin CurrentUser user) {
        if (photoRef == null || photoRef.isBlank()) {
            throw new ApiException(422, "photo_ref обязателен");
        }
        int rows = jdbc.update(
            "UPDATE tickets SET delivery_photo_status = 'rejected', "
            + "delivery_photo_reviewed_at = NOW() "
            + "WHERE id = ? AND delivery_photo = ? AND delivery_photo_status = 'pending'",
            ticketId, photoRef);
        if (rows == 0) {
            throw new ApiException(409,
                "Фото уже изменилось или обработано — обновите очередь перед решением");
        }
        deliveryPhotos.deleteAfterCommit(photoRef);
        audit.log(user.sub(), "photo_reject", "ticket", ticketId,
            "Admin rejected delivery photo for ticket #" + ticketId);
        return Map.of("ok", true, "status", "rejected");
    }
    @GetMapping("/stats")
    public Map<String, Object> adminStats(@Admin CurrentUser user) {
        double kgSaved = nz(jdbc.queryForObject(
            "SELECT COALESCE(SUM(" + EsgService.RESCUED_KG_SQL + "),0) AS kg_saved "
            + "FROM lots l WHERE " + EsgService.RESCUED_SQL, Double.class));
        int deliveries = nz(jdbc.queryForObject(
            "SELECT COUNT(*) FROM tickets WHERE status = 'fulfilled'", Integer.class));
        int activeVolunteers = nz(jdbc.queryForObject(
            "SELECT COUNT(DISTINCT volunteer_id) FROM volunteer_routes "
            + "WHERE started_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'", Integer.class));
        int totalLots = nz(jdbc.queryForObject("SELECT COUNT(*) FROM lots", Integer.class));
        int expiredLots = nz(jdbc.queryForObject(
            "SELECT COUNT(*) FROM lots WHERE status = 'expired'", Integer.class));
        double avgMin = nz(jdbc.queryForObject(
            "SELECT AVG(EXTRACT(EPOCH FROM (finished_at - started_at)) / 60) "
            + "FROM volunteer_routes WHERE status = 'finished' AND finished_at IS NOT NULL",
            Double.class));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kg_food_saved", kgSaved);
        out.put("deliveries_completed", deliveries);
        out.put("active_volunteers", activeVolunteers);
        out.put("avg_delivery_minutes", round1(avgMin));
        out.put("percent_expired_lots",
            round1(totalLots != 0 ? (double) expiredLots / totalLots * 100 : 0.0));
        return out;
    }
    @GetMapping("/heatmap")
    public List<Map<String, Object>> supplyDemandHeatmap(@Admin CurrentUser user) {
        String lotCity = "COALESCE(NULLIF(TRIM(city), ''), 'Без города')";
        String npCity = "COALESCE(NULLIF(TRIM(np.city), ''), 'Без города')";
        Map<String, Map<String, Object>> supply = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT " + lotCity + " AS city, COUNT(*) AS active_lots, "
                + "COALESCE(SUM(quantity * unit_weight_kg), 0) AS active_kg "
                + "FROM lots WHERE status = 'active' GROUP BY 1")) {
            supply.put((String) r.get("city"), r);
        }
        Map<String, Integer> demandTickets = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT " + npCity + " AS city, COUNT(*) AS open_tickets "
                + "FROM tickets t LEFT JOIN needy_profile np ON np.needy_id = t.needy_id "
                + "WHERE t.status = 'open' GROUP BY 1")) {
            demandTickets.put((String) r.get("city"), toInt(r.get("open_tickets")));
        }
        Map<String, Integer> activeNeedy = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT " + npCity + " AS city, COUNT(*) AS active_needy "
                + "FROM needy_profile np JOIN needy n ON n.id = np.needy_id AND n.status = 'active' "
                + "GROUP BY 1")) {
            activeNeedy.put((String) r.get("city"), toInt(r.get("active_needy")));
        }
        Map<String, Integer> volunteers = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT " + lotCity + " AS city, COUNT(*) AS volunteers FROM volunteers GROUP BY 1")) {
            volunteers.put((String) r.get("city"), toInt(r.get("volunteers")));
        }
        Map<String, Integer> availableNow = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT city, availability FROM volunteers")) {
            String c = trimOrDefault((String) r.get("city"));
            Object av = r.get("availability");
            if (availability.isAvailableNow(av == null ? null : av.toString())) {
                availableNow.merge(c, 1, Integer::sum);
            }
        }
        Set<String> cities = new HashSet<>();
        cities.addAll(supply.keySet());
        cities.addAll(demandTickets.keySet());
        cities.addAll(activeNeedy.keySet());
        cities.addAll(volunteers.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String c : cities) {
            Map<String, Object> s = supply.get(c);
            int activeLots = s == null ? 0 : toInt(s.get("active_lots"));
            int openTickets = demandTickets.getOrDefault(c, 0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("city", c);
            row.put("active_lots", activeLots);
            row.put("active_kg", s == null ? 0.0 : toDouble(s.get("active_kg")));
            row.put("open_tickets", openTickets);
            row.put("active_needy", activeNeedy.getOrDefault(c, 0));
            row.put("volunteers", volunteers.getOrDefault(c, 0));
            row.put("volunteers_available", availableNow.getOrDefault(c, 0));
            row.put("gap", openTickets - activeLots);
            rows.add(row);
        }
        rows.sort((a, b) -> Integer.compare((int) b.get("gap"), (int) a.get("gap")));
        return rows;
    }
    @GetMapping("/routes")
    public List<Map<String, Object>> listActiveRoutes(@Admin CurrentUser user) {
        return jdbc.queryForList(
            "SELECT vr.id, vr.volunteer_id, vr.lot_id, vr.status, vr.started_at, vr.finished_at, "
            + "v.name AS volunteer_name "
            + "FROM volunteer_routes vr LEFT JOIN volunteers v ON vr.volunteer_id = v.id "
            + "WHERE vr.status = 'in_progress' ORDER BY vr.started_at DESC");
    }
    @PostMapping("/routes/{routeId}/reset")
    @Transactional
    public Map<String, Object> resetRoute(@PathVariable int routeId, @Admin CurrentUser user) {
        List<Map<String, Object>> routes = jdbc.queryForList(
            "SELECT * FROM volunteer_routes WHERE id = ? FOR UPDATE", routeId);
        if (routes.isEmpty()) {
            throw new ApiException(404, "Route not found");
        }
        Map<String, Object> route = routes.get(0);
        if (!"in_progress".equals(route.get("status"))) {
            throw new ApiException(400, "Маршрут уже завершён или сброшен");
        }
        Object lotIdObj = route.get("lot_id");
        Integer lotId = lotIdObj instanceof Number n ? n.intValue() : null;
        Object pointsObj = route.get("points");
        routeRevert.revertRouteLot(lotId, pointsObj == null ? null : pointsObj.toString());
        jdbc.update(
            "UPDATE volunteer_routes SET points = ?, status = 'timed_out', finished_at = NOW() WHERE id = ?",
            RoutePointPrivacy.redactAllTicketPointsJson(pointsObj), routeId);
        audit.log(user.sub(), "route_reset", "route", routeId, "Admin reset route #" + routeId);
        return Map.of("ok", true);
    }
    @PostMapping("/lots/{lotId}/reset")
    @Transactional
    public Map<String, Object> resetLot(@PathVariable int lotId, @Admin CurrentUser user) {
        List<Integer> reset = jdbc.query(
            "UPDATE lots l SET status = 'active', taken_at = NULL, taken_by = NULL "
            + "WHERE l.id = ? AND l.status = 'taken' "
            + "AND (l.expiry_date IS NULL OR l.expiry_date > CURRENT_DATE + INTERVAL '1 day') "
            + "AND NOT EXISTS (SELECT 1 FROM volunteer_routes vr WHERE vr.lot_id = l.id) "
            + "AND NOT EXISTS (SELECT 1 FROM tickets t WHERE t.lot_id = l.id "
            + "AND t.status IN ('assigned', 'fulfilled')) "
            + "RETURNING l.id",
            (rs, n) -> rs.getInt("id"), lotId);
        if (reset.isEmpty()) {
            boolean exists = !jdbc.query(
                "SELECT id FROM lots WHERE id = ?", (rs, n) -> rs.getInt("id"), lotId).isEmpty();
            if (!exists) {
                throw new ApiException(404, "Lot not found");
            }
            throw new ApiException(409, "Lot cannot be reset from its current state");
        }
        audit.log(user.sub(), "lot_reset", "lot", lotId, "Admin reset lot #" + lotId);
        return Map.of("ok", true);
    }
    @GetMapping("/users")
    public List<Map<String, Object>> listUsers(@Admin CurrentUser user) {
        return jdbc.queryForList(
            "SELECT id, username, role, related_id, is_blocked, created_at "
            + "FROM users ORDER BY created_at DESC");
    }
    @PostMapping("/users/{userId}/block")
    public Map<String, Object> blockUser(@PathVariable int userId, @Admin CurrentUser user) {
        if (jdbc.update("UPDATE users SET is_blocked = TRUE WHERE id = ?", userId) == 0) {
            throw new ApiException(404, "User not found");
        }
        audit.log(user.sub(), "user_block", "user", userId, "Admin blocked user #" + userId);
        return Map.of("ok", true);
    }
    @PostMapping("/users/{userId}/unblock")
    public Map<String, Object> unblockUser(@PathVariable int userId, @Admin CurrentUser user) {
        if (jdbc.update("UPDATE users SET is_blocked = FALSE WHERE id = ?", userId) == 0) {
            throw new ApiException(404, "User not found");
        }
        audit.log(user.sub(), "user_unblock", "user", userId, "Admin unblocked user #" + userId);
        return Map.of("ok", true);
    }
    @GetMapping("/esg")
    public Map<String, Object> adminEsg(@RequestParam(defaultValue = "12") int months,
                                        @Admin CurrentUser user) {
        return esgService.globalReport(months);
    }
    @GetMapping("/shops")
    public List<Map<String, Object>> listShops(@Admin CurrentUser user) {
        return jdbc.queryForList(
            "SELECT id, name, contact, city, plan, created_at FROM shops ORDER BY created_at DESC");
    }
    @PatchMapping("/shops/{shopId}/plan")
    public Map<String, Object> setShopPlan(@PathVariable int shopId, @RequestBody PlanUpdate payload,
                                           @Admin CurrentUser user) {
        if (!Plans.isValid(payload.plan())) {
            throw new ApiException(400, "Unknown plan: " + payload.plan());
        }
        if (jdbc.update("UPDATE shops SET plan = ? WHERE id = ?", payload.plan(), shopId) == 0) {
            throw new ApiException(404, "Shop not found");
        }
        audit.log(user.sub(), "plan_change", "shop", shopId,
            "Admin set plan '" + payload.plan() + "' for shop #" + shopId);
        return Map.of("ok", true, "plan", payload.plan());
    }
    @GetMapping("/audit")
    public List<Map<String, Object>> getAuditLog(@RequestParam(defaultValue = "50") int limit,
                                                 @RequestParam(defaultValue = "0") int offset,
                                                 @Admin CurrentUser user) {
        return jdbc.queryForList(
            "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT ? OFFSET ?",
            Clamp.clamp(limit, 1, 100), Math.max(0, offset));
    }
    private static double round1(double x) {
        return BigDecimal.valueOf(x).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }
    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }
    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
    private static String trimOrDefault(String city) {
        String c = city == null ? "" : city.trim();
        return c.isEmpty() ? "Без города" : c;
    }
    private Path deliveryPhotoPath(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String dir = ref.startsWith("/delivery_photos/") ? deliveryPhotoUploadDir
            : ref.startsWith("/volunteer_uploads/") ? volunteerUploadDir : null;
        if (dir == null) {
            return null;
        }
        try {
            Path base = Paths.get(dir).toAbsolutePath().normalize();
            Path candidate = base.resolve(Paths.get(ref).getFileName()).normalize();
            return candidate.startsWith(base) ? candidate : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
    private static MediaType mediaTypeFor(String filename) {
        String f = filename.toLowerCase(java.util.Locale.ROOT);
        if (f.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (f.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }
}
