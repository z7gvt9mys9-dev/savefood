package ru.savefood.admin;

import java.io.IOException;
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
import ru.savefood.needy.NeedyService;
import ru.savefood.security.Admin;
import ru.savefood.security.CurrentUser;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.AvailabilityService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * Java port of backend/admin/routes.py — the admin/moderation surface. Every
 * handler takes an {@code @Admin CurrentUser}, the analogue of
 * {@code Depends(require_admin)}: a valid admin token is required, blocked users
 * and non-admins are rejected before the body runs.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final JdbcTemplate jdbc;
    private final NeedyService needyService;
    private final VolunteerRepository volunteerRepo;
    private final EsgService esgService;
    private final AuditService audit;
    private final RouteRevertService routeRevert;
    private final AvailabilityService availability;
    private final TelegramService telegram;
    private final String volunteerUploadDir;

    public AdminController(JdbcTemplate jdbc, NeedyService needyService,
                           VolunteerRepository volunteerRepo, EsgService esgService,
                           AuditService audit, RouteRevertService routeRevert,
                           AvailabilityService availability, TelegramService telegram,
                           @Value("${savefood.volunteer-upload-dir}") String volunteerUploadDir) {
        this.jdbc = jdbc;
        this.needyService = needyService;
        this.volunteerRepo = volunteerRepo;
        this.esgService = esgService;
        this.audit = audit;
        this.routeRevert = routeRevert;
        this.availability = availability;
        this.telegram = telegram;
        this.volunteerUploadDir = volunteerUploadDir;
    }

    // ── Moderation ───────────────────────────────────────────────────────────

    /**
     * Manual KYC moderation (§5) restored on top of Auto-KYC.
     *
     * <p>Auto-KYC keeps deciding the confident cases on its own; everything it is
     * unsure about (verdict {@code review}) used to sit in {@code pending} forever
     * because no human endpoint existed. These two handlers are that missing
     * escape hatch — and they also let an admin overturn a wrong automatic
     * decision, so the status is set unconditionally rather than guarded on
     * {@code pending}.
     *
     * <p><b>The moderator never sees the document itself.</b> That is deliberate
     * (§58.1: no human access to the file, to remove the leak path). The decision
     * is made from what the AI extracted — {@code kyc_verdict}, {@code kyc_score}
     * and {@code kyc_notes} (document type + summary + the reasons that moved the
     * score) — which the queue endpoints below return.
     */
    @PatchMapping("/needy/{needyId}/moderation")
    public Map<String, Object> moderateNeedy(@PathVariable int needyId,
                                             @RequestBody ModerationDecision payload,
                                             @Admin CurrentUser user) {
        String status = requireDecision(payload);
        Map<String, Object> updated = needyService.setNeedyStatusManually(needyId, status);
        if (updated == null) {
            throw new ApiException(404, "Needy not found");
        }
        audit.log(user.sub(), "kyc_manual_" + status, "needy", needyId,
            "Ручное решение модератора: " + status + reasonSuffix(payload));
        notifyModerationOutcome("needy", needyId, status,
            "Ваша анкета одобрена модератором — можете создавать заявки на получение продуктов.",
            "Документ не принят модератором. Загрузите корректный документ, подтверждающий "
            + "право на помощь.");
        return updated;
    }

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
        notifyModerationOutcome("volunteer", volunteerId, status,
            "Ваш аккаунт волонтёра подтверждён модератором — можно брать маршруты.",
            "Удостоверение не принято модератором. Загрузите корректный документ, "
            + "удостоверяющий личность, чтобы брать маршруты.");
        return updated;
    }

    /** Moderation queue: recipients, newest first. {@code status=pending} for the backlog. */
    @GetMapping("/needy")
    public List<Map<String, Object>> listNeedy(@RequestParam(required = false) String status,
                                               @Admin CurrentUser user) {
        return needyService.getAllNeedy(status);
    }

    /** Moderation queue: volunteers (§58). Mirrors {@link #listNeedy}. */
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

    /**
     * In-app row + best-effort external ping, mirroring the Auto-KYC wording so a
     * manual decision is indistinguishable from an automatic one to the applicant.
     *
     * @param role {@code "needy"} or {@code "volunteer"} — picks both the
     *             notifications column and the Telegram fan-out target
     */
    private void notifyModerationOutcome(String role, int recipientId, String status,
                                         String approvedMsg, String rejectedMsg) {
        boolean approved = "approved".equals(status);
        String message = approved ? approvedMsg : rejectedMsg;
        jdbc.update(
            "INSERT INTO notifications (" + ("needy".equals(role) ? "needy_id" : "volunteer_id")
            + ", type, payload, created_at, read) VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0)",
            recipientId, approved ? "moderation_approved" : "moderation_rejected", message);
        try {
            String prefixed = (approved ? "✅ " : "⚠️ ") + message;
            if ("needy".equals(role)) {
                telegram.notifyNeedy(recipientId, prefixed);
            } else {
                telegram.notifyVolunteer(recipientId, prefixed);
            }
        } catch (RuntimeException ignore) {
            // best-effort, like the Auto-KYC path
        }
    }

    // ── Delivery photo moderation (public Impact feed, §36.1) ──────────────────

    @GetMapping("/delivery_photos")
    public List<Map<String, Object>> listDeliveryPhotos(
            @RequestParam(defaultValue = "pending") String status, @Admin CurrentUser user) {
        if (!Set.of("pending", "approved", "rejected").contains(status)) {
            throw new ApiException(400, "Invalid status");
        }
        return jdbc.queryForList(
            "SELECT t.id AS ticket_id, t.delivery_photo, t.delivery_photo_status, "
            + "t.delivery_photo_ai_verdict, t.delivery_photo_ai_score, "
            + "t.delivery_photo_ai_notes, t.fulfilled_at, l.category, l.city "
            + "FROM tickets t LEFT JOIN lots l ON l.id = t.lot_id "
            + "WHERE t.delivery_photo IS NOT NULL AND t.delivery_photo_status = ? "
            + "ORDER BY t.fulfilled_at DESC NULLS LAST LIMIT 100",
            status);
    }

    @PostMapping("/delivery_photos/{ticketId}/approve")
    public Map<String, Object> approveDeliveryPhoto(@PathVariable int ticketId, @Admin CurrentUser user) {
        // A rejected photo's file is already deleted — approving it would push a
        // broken image into the public feed, so it's a hard no.
        int rows = jdbc.update(
            "UPDATE tickets SET delivery_photo_status = 'approved', "
            + "delivery_photo_reviewed_at = NOW() "
            + "WHERE id = ? AND delivery_photo IS NOT NULL "
            + "AND delivery_photo_status <> 'rejected'",
            ticketId);
        if (rows == 0) {
            List<String> cur = jdbc.query(
                "SELECT delivery_photo_status AS s FROM tickets WHERE id = ? AND delivery_photo IS NOT NULL",
                (rs, n) -> rs.getString("s"), ticketId);
            if (!cur.isEmpty() && "rejected".equals(cur.get(0))) {
                throw new ApiException(409,
                    "Файл фото уже удалён при отклонении — нужна новая загрузка от получателя");
            }
            throw new ApiException(404, "Photo not found");
        }
        audit.log(user.sub(), "photo_approve", "ticket", ticketId,
            "Admin approved delivery photo for ticket #" + ticketId);
        return Map.of("ok", true, "status", "approved");
    }

    @PostMapping("/delivery_photos/{ticketId}/reject")
    public Map<String, Object> rejectDeliveryPhoto(@PathVariable int ticketId, @Admin CurrentUser user) {
        List<String> photos = jdbc.query(
            "SELECT delivery_photo FROM tickets WHERE id = ?",
            (rs, n) -> rs.getString("delivery_photo"), ticketId);
        String photo = photos.isEmpty() ? null : photos.get(0);
        if (photo == null || photo.isEmpty()) {
            throw new ApiException(404, "Photo not found");
        }
        jdbc.update(
            "UPDATE tickets SET delivery_photo_status = 'rejected', "
            + "delivery_photo_reviewed_at = NOW() WHERE id = ?",
            ticketId);
        // Remove the file so a rejected (e.g. trolling) image can never leak.
        if (photo.startsWith("/volunteer_uploads/")) {
            Path path = Paths.get(volunteerUploadDir, Paths.get(photo).getFileName().toString());
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // best-effort, like the Python except OSError: pass
            }
        }
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

    // ── Supply / demand heatmap (§51) ──────────────────────────────────────────

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

        Map<String, Integer> approved = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT " + npCity + " AS city, COUNT(*) AS approved_needy "
                + "FROM needy_profile np JOIN needy n ON n.id = np.needy_id AND n.status = 'approved' "
                + "GROUP BY 1")) {
            approved.put((String) r.get("city"), toInt(r.get("approved_needy")));
        }

        Map<String, Integer> volunteers = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT " + lotCity + " AS city, COUNT(*) AS volunteers FROM volunteers GROUP BY 1")) {
            volunteers.put((String) r.get("city"), toInt(r.get("volunteers")));
        }

        // Availability calendar (§54): volunteers free *right now* per city.
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
        cities.addAll(approved.keySet());
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
            row.put("approved_needy", approved.getOrDefault(c, 0));
            row.put("volunteers", volunteers.getOrDefault(c, 0));
            row.put("volunteers_available", availableNow.getOrDefault(c, 0));
            row.put("gap", openTickets - activeLots); // >0 = unmet demand
            rows.add(row);
        }
        rows.sort((a, b) -> Integer.compare((int) b.get("gap"), (int) a.get("gap")));
        return rows;
    }

    // ── Routes dispatcher ──────────────────────────────────────────────────────

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
            "SELECT * FROM volunteer_routes WHERE id = ?", routeId);
        if (routes.isEmpty()) {
            throw new ApiException(404, "Route not found");
        }
        Map<String, Object> route = routes.get(0);
        // Only an in-progress route can be reset: resetting a finished one would
        // flip its (already delivered) lot back to 'active' on the map.
        if (!"in_progress".equals(route.get("status"))) {
            throw new ApiException(400, "Маршрут уже завершён или сброшен");
        }
        Object lotIdObj = route.get("lot_id");
        Integer lotId = lotIdObj instanceof Number n ? n.intValue() : null;
        Object pointsObj = route.get("points");
        routeRevert.revertRouteLot(lotId, pointsObj == null ? null : pointsObj.toString());
        jdbc.update(
            "UPDATE volunteer_routes SET status = 'timed_out', finished_at = NOW() WHERE id = ?",
            routeId);
        audit.log(user.sub(), "route_reset", "route", routeId, "Admin reset route #" + routeId);
        return Map.of("ok", true);
    }

    // ── Lot management ──────────────────────────────────────────────────────────

    @PostMapping("/lots/{lotId}/reset")
    public Map<String, Object> resetLot(@PathVariable int lotId, @Admin CurrentUser user) {
        Integer found = jdbc.query("SELECT id FROM lots WHERE id = ?",
            (rs, n) -> rs.getInt("id"), lotId).stream().findFirst().orElse(null);
        if (found == null) {
            throw new ApiException(404, "Lot not found");
        }
        jdbc.update(
            "UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL WHERE id = ?",
            lotId);
        audit.log(user.sub(), "lot_reset", "lot", lotId, "Admin reset lot #" + lotId);
        return Map.of("ok", true);
    }

    // ── User management ─────────────────────────────────────────────────────────

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

    // ── ESG & billing ───────────────────────────────────────────────────────────

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
            "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT ? OFFSET ?", limit, offset);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

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
}
