package ru.savefood.background;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import ru.savefood.kyc.KycService;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.RouteRevertService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Port of backend/background.py — the maintenance ticks (lot expiry, route
 * timeouts, GPS anti-fraud, reservation TTL, KYC retry/retention) that the
 * Python {@code worker} process runs. Here they are Spring {@code @Scheduled}
 * methods on the same cadence the Python loops use.
 *
 * <p><b>Disabled by default</b> ({@code savefood.background-tasks=off}): during the
 * migration the authoritative worker stays the Python {@code python -m
 * backend.worker}, and running both would double-fire every tick. Flip
 * {@code savefood.background-tasks=embedded} only once the Python worker is turned
 * off (its {@code BACKGROUND_TASKS=off}) so exactly one scheduler owns the DB.
 */
@Service
public class MaintenanceTasks {

    private static final Logger log = Logger.getLogger(MaintenanceTasks.class.getName());

    // Route-release timeouts (§8/§9), matching background.py.
    private static final int REASSIGN_TIMEOUT_MINUTES = 90;
    private static final int MAX_ROUTE_DURATION_MINUTES = 240;
    // Anti-fraud tuning (§27).
    private static final int ANTIFRAUD_CHECK_AFTER_MINUTES = 15;
    private static final int ANTIFRAUD_GRACE_MINUTES = 15;
    private static final double ANTIFRAUD_DRIFT_THRESHOLD_M = 300;

    private record AntifraudAction(String kind, int volunteerId, Integer lotId) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RouteRevertService revert;
    private final KycService kyc;
    private final TelegramService telegram;

    private final boolean enabled;
    private final String supportChatId;
    private final String volunteerKycDir;
    private final String needyUploadDir;
    private final int kycRetryBatch;
    private final int kycRetentionHours;

    public MaintenanceTasks(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                            RouteRevertService revert, KycService kyc, TelegramService telegram,
                            @Value("${savefood.background-tasks:off}") String mode,
                            @Value("${savefood.support-chat-id:}") String supportChatId,
                            @Value("${savefood.volunteer-kyc-upload-dir:../backend/volunteer/kyc_uploads}") String volunteerKycDir,
                            @Value("${savefood.needy-upload-dir:../backend/needy/uploads}") String needyUploadDir,
                            @Value("${savefood.kyc-retry-batch:100}") int kycRetryBatch,
                            @Value("${savefood.kyc-doc-retention-hours:0}") int kycRetentionHours) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.revert = revert;
        this.kyc = kyc;
        this.telegram = telegram;
        this.enabled = "embedded".equalsIgnoreCase(mode.strip());
        this.supportChatId = supportChatId == null ? "" : supportChatId;
        this.volunteerKycDir = volunteerKycDir;
        this.needyUploadDir = needyUploadDir;
        this.kycRetryBatch = kycRetryBatch;
        this.kycRetentionHours = kycRetentionHours;
    }

    // ── expire_tick (every 30 min) ───────────────────────────────────────────

    @Scheduled(fixedDelay = 30 * 60_000, initialDelay = 60_000)
    public void expireTick() {
        if (!enabled) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, shop_id FROM lots WHERE status = 'active' AND expiry_date IS NOT NULL "
                + "AND expiry_date <= CURRENT_DATE + INTERVAL '1 day'");
            int n = 0;
            for (Map<String, Object> r : rows) {
                int lotId = ((Number) r.get("id")).intValue();
                Integer shopId = r.get("shop_id") == null ? null : ((Number) r.get("shop_id")).intValue();
                try {
                    tx.executeWithoutResult(s -> {
                        jdbc.update("UPDATE lots SET status = 'expired' WHERE id = ?", lotId);
                        jdbc.update(
                            "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) "
                            + "VALUES (?, ?, ?, ?, ?, 0)",
                            shopId, lotId, "lot_expired_soon",
                            "Лот #" + lotId + " снят: до истечения срока годности менее 24 часов", now());
                        cancelLotOpenTickets(lotId, "лоту осталось менее 24 часов до истечения срока");
                    });
                    n++;
                } catch (RuntimeException ignore) {
                    // mirror the per-lot try/except in expire_soon_lots
                }
            }
            if (n > 0) {
                log.info("[background] expire_tick: " + n + " lots expired");
            }
        } catch (RuntimeException e) {
            log.warning("[background] expire tick failed: " + e.getMessage());
        }
    }

    // ── reassign_tick (every 10 min) ─────────────────────────────────────────

    @Scheduled(fixedDelay = 10 * 60_000, initialDelay = 90_000)
    public void reassignTick() {
        if (!enabled) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM volunteer_routes WHERE status = 'in_progress' AND ("
                + "COALESCE(last_activity_at, started_at) <= CURRENT_TIMESTAMP - make_interval(mins => ?) "
                + "OR started_at <= CURRENT_TIMESTAMP - make_interval(mins => ?))",
                REASSIGN_TIMEOUT_MINUTES, MAX_ROUTE_DURATION_MINUTES);
            for (Map<String, Object> row : rows) {
                int routeId = ((Number) row.get("id")).intValue();
                // Per-route transaction = the SAVEPOINT isolation of the Python tick:
                // a failed revert rolls back only this route and leaves it
                // 'in_progress' for the next tick instead of stranding the lot.
                Map<String, Object> timedOutRoute;
                try {
                    timedOutRoute = tx.execute(s -> {
                        // The outer scan is intentionally cheap and stale. Lock
                        // the current row, re-evaluate its timeout predicate, then
                        // take tickets only after the route lock (the same order as
                        // complete/attempt/finish interactive requests).
                        List<Map<String, Object>> locked = jdbc.queryForList(
                            "SELECT * FROM volunteer_routes WHERE id = ? AND status = 'in_progress' AND ("
                            + "COALESCE(last_activity_at, started_at) <= CURRENT_TIMESTAMP "
                            + "- make_interval(mins => ?) OR started_at <= CURRENT_TIMESTAMP "
                            + "- make_interval(mins => ?)) FOR UPDATE",
                            routeId, REASSIGN_TIMEOUT_MINUTES, MAX_ROUTE_DURATION_MINUTES);
                        if (locked.isEmpty()) {
                            return null;
                        }
                        Map<String, Object> current = locked.get(0);
                        Integer lotId = current.get("lot_id") == null ? null
                            : ((Number) current.get("lot_id")).intValue();
                        String points = (String) current.get("points");
                        revert.revertRouteLot(lotId, points);
                        int updated = jdbc.update("UPDATE volunteer_routes SET status = 'timed_out', "
                            + "finished_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'in_progress'", routeId);
                        return updated == 1 ? current : null;
                    });
                } catch (RuntimeException e) {
                    log.warning("[background] reassign: revert failed for route " + routeId
                        + "; left in_progress for retry");
                    continue;
                }
                if (timedOutRoute == null) {
                    continue;
                }
                if (!supportChatId.isEmpty()) {
                    try {
                        telegram.sendMessage(supportChatId, "⚠️ Маршрут #" + routeId + " волонтёра "
                            + timedOutRoute.get("volunteer_id") + " переназначен по таймауту.");
                    } catch (Exception ignore) {
                        // best-effort
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warning("[background] reassign tick failed: " + e.getMessage());
        }
    }

    // ── antifraud_tick (every 3 min) ─────────────────────────────────────────

    @Scheduled(fixedDelay = 3 * 60_000, initialDelay = 120_000)
    public void antifraudTick() {
        if (!enabled) {
            return;
        }
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                "SELECT vr.*, v.lat AS v_lat, v.lon AS v_lon FROM volunteer_routes vr "
                + "JOIN volunteers v ON v.id = vr.volunteer_id WHERE vr.status = 'in_progress' "
                + "AND vr.start_dist_m IS NOT NULL "
                + "AND vr.started_at <= CURRENT_TIMESTAMP - make_interval(mins => ?)",
                ANTIFRAUD_CHECK_AFTER_MINUTES);
        } catch (RuntimeException e) {
            log.warning("[background] antifraud tick failed: " + e.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            try {
                antifraudOne(row);
            } catch (RuntimeException e) {
                log.warning("[background] antifraud: route " + row.get("id") + " failed: " + e.getMessage());
            }
        }
    }

    private void antifraudOne(Map<String, Object> snapshot) {
        int routeId = ((Number) snapshot.get("id")).intValue();
        AntifraudAction action = tx.execute(s -> {
            // The periodic scan is only a candidate list. Lock the live route
            // before reading points or writing a warning/reset, then take ticket
            // locks through RouteRevertService afterwards (route → ticket order).
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT vr.*, v.lat AS v_lat, v.lon AS v_lon FROM volunteer_routes vr "
                    + "JOIN volunteers v ON v.id = vr.volunteer_id "
                    + "WHERE vr.id = ? AND vr.status = 'in_progress' "
                    + "AND vr.start_dist_m IS NOT NULL "
                    + "AND vr.started_at <= CURRENT_TIMESTAMP - make_interval(mins => ?) FOR UPDATE OF vr",
                routeId, ANTIFRAUD_CHECK_AFTER_MINUTES);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            JsonNode points = readJson((String) row.get("points"));
            JsonNode shopPoint = null;
            if (points != null && points.isArray()) {
                for (JsonNode p : points) {
                    if ("shop".equals(p.path("kind").asText())) {
                        shopPoint = p;
                        break;
                    }
                }
            }
            // Pickup confirmed → drift monitoring is over for this route.
            if (shopPoint == null || shopPoint.path("done").asBoolean(false)
                    || !shopPoint.path("lat").isNumber() || !shopPoint.path("lon").isNumber()) {
                return null;
            }
            Object vLat = row.get("v_lat");
            Object vLon = row.get("v_lon");
            if (!(vLat instanceof Number) || !(vLon instanceof Number)) {
                return null;
            }
            int volunteerId = ((Number) row.get("volunteer_id")).intValue();
            Integer lotId = row.get("lot_id") == null ? null : ((Number) row.get("lot_id")).intValue();
            String pointsJson = (String) row.get("points");
            double distM = haversine(((Number) vLat).doubleValue(), ((Number) vLon).doubleValue(),
                shopPoint.path("lat").asDouble(), shopPoint.path("lon").asDouble());
            boolean movingAway = distM > ((Number) row.get("start_dist_m")).doubleValue()
                + ANTIFRAUD_DRIFT_THRESHOLD_M;
            Object pingAt = row.get("antifraud_ping_at");

            if (!movingAway) {
                if (pingAt != null) {
                    jdbc.update("UPDATE volunteer_routes SET antifraud_ping_at = NULL "
                        + "WHERE id = ? AND status = 'in_progress'", routeId);
                }
                return null;
            }
            if (pingAt == null) {
                String msg = antifraudPingMessage(lotId);
                int updated = jdbc.update("UPDATE volunteer_routes SET antifraud_ping_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ? AND status = 'in_progress'", routeId);
                if (updated == 0) {
                    return null;
                }
                jdbc.update("INSERT INTO notifications (volunteer_id, type, payload, created_at, read) "
                    + "VALUES (?, ?, ?, ?, 0)", volunteerId, "antifraud_ping", msg, now());
                return new AntifraudAction("ping", volunteerId, lotId);
            }
            Instant pinged = toInstant(pingAt);
            if (pinged == null || Instant.now().isBefore(pinged.plusSeconds(ANTIFRAUD_GRACE_MINUTES * 60L))) {
                return null;  // still inside the grace window (or legacy invalid timestamp)
            }
            // No reaction: release tickets, revive the lot, close the route — atomically.
            revert.revertRouteLot(lotId, pointsJson);
            int updated = jdbc.update("UPDATE volunteer_routes SET status = 'timed_out', finished_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND status = 'in_progress'", routeId);
            if (updated == 0) {
                return null;
            }
            jdbc.update("INSERT INTO notifications (volunteer_id, type, payload, created_at, read) "
                + "VALUES (?, ?, ?, ?, 0)", volunteerId, "route_reset",
                "Маршрут #" + routeId + " снят: вы не приближались к магазину после предупреждения. "
                + "Лот возвращён на витрину.", now());
            return new AntifraudAction("reset", volunteerId, lotId);
        });
        if (action == null) {
            return;
        }
        if ("ping".equals(action.kind())) {
            try {
                telegram.notifyVolunteer(action.volunteerId(), antifraudPingMessage(action.lotId()));
            } catch (Exception ignore) {
                // best-effort
            }
            return;
        }
        if (!supportChatId.isEmpty()) {
            try {
                telegram.sendMessage(supportChatId, "🚨 Антифрод: маршрут #" + routeId + " волонтёра "
                    + action.volunteerId() + " снят (удалялся от магазина, лот #" + action.lotId()
                    + " возвращён).");
            } catch (Exception ignore) {
                // best-effort
            }
        }
    }

    // ── reservation_ttl_tick (every 5 min) ───────────────────────────────────

    @Scheduled(fixedDelay = 5 * 60_000, initialDelay = 150_000)
    public void reservationTtlTick() {
        if (!enabled) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> {
                List<Map<String, Object>> expired = jdbc.queryForList(
                    "SELECT id, needy_id, lot_id, quantity, self_pickup FROM tickets WHERE status = 'open' "
                    + "AND expires_at IS NOT NULL AND expires_at < CURRENT_TIMESTAMP");
                for (Map<String, Object> t : expired) {
                    int id = ((Number) t.get("id")).intValue();
                    Integer needyId = t.get("needy_id") == null ? null : ((Number) t.get("needy_id")).intValue();
                    Integer lotId = t.get("lot_id") == null ? null : ((Number) t.get("lot_id")).intValue();
                    Number quantity = (Number) t.get("quantity");
                    boolean selfPickup = Boolean.TRUE.equals(t.get("self_pickup"));

                    jdbc.update("UPDATE tickets SET status = 'cancelled' WHERE id = ?", id);
                    if (lotId != null) {
                        jdbc.update("UPDATE lots SET quantity = quantity + ? WHERE id = ? AND status = 'active'",
                            quantity, lotId);
                    }
                    String msg = selfPickup
                        ? "⏳ Срок брони лота #" + lotId + " (самовывоз) истёк. Заявка отменена, "
                          + "еда вернулась на витрину."
                        : "⏳ Бронь лота #" + lotId + " истекла: волонтёр не взялся за доставку. "
                          + "Заявка отменена, еда вернулась на витрину — лимит не потрачен.";
                    String ntype = selfPickup ? "self_pickup_expired" : "reservation_expired";
                    jdbc.update("INSERT INTO notifications (needy_id, type, payload, created_at, read) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0)", needyId, ntype, msg);
                    if (needyId != null) {
                        try {
                            telegram.notifyNeedy(needyId, "⏳ " + msg);
                        } catch (Exception ignore) {
                            // best-effort
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            log.warning("[background] reservation_ttl tick failed: " + e.getMessage());
        }
    }

    // ── kyc_retry_tick (every 15 min) ────────────────────────────────────────

    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 180_000)
    public void kycRetryTick() {
        if (!enabled) {
            return;
        }
        try {
            List<Map<String, Object>> vols = jdbc.queryForList(
                "SELECT id, name, document FROM volunteers WHERE status = 'pending' AND document IS NOT NULL "
                + "AND (kyc_verdict IS NULL OR kyc_verdict = 'unchecked') "
                + "ORDER BY kyc_checked_at ASC NULLS FIRST LIMIT ?", kycRetryBatch);
            for (Map<String, Object> v : vols) {
                String path = safeDocPath(volunteerKycDir, (String) v.get("document"));
                if (path != null) {
                    kyc.startVolunteerKycCheck(((Number) v.get("id")).intValue(), path,
                        v.get("name") == null ? "" : v.get("name").toString());
                }
            }
            List<Map<String, Object>> needies = jdbc.queryForList(
                "SELECT n.id AS id, n.name AS name, p.document AS document FROM needy n "
                + "JOIN needy_profile p ON p.needy_id = n.id WHERE n.status = 'pending' "
                + "AND p.document IS NOT NULL AND (n.kyc_verdict IS NULL OR n.kyc_verdict = 'unchecked') "
                + "ORDER BY n.kyc_checked_at ASC NULLS FIRST LIMIT ?", kycRetryBatch);
            for (Map<String, Object> n : needies) {
                String path = safeDocPath(needyUploadDir, (String) n.get("document"));
                if (path != null) {
                    kyc.startKycCheck(((Number) n.get("id")).intValue(), path,
                        n.get("name") == null ? "" : n.get("name").toString());
                }
            }
        } catch (RuntimeException e) {
            log.warning("[background] kyc_retry tick failed: " + e.getMessage());
        }
    }

    // ── kyc_doc_retention_tick (hourly; disabled unless retention > 0) ────────

    @Scheduled(fixedDelay = 60 * 60_000, initialDelay = 300_000)
    public void kycDocRetentionTick() {
        if (!enabled || kycRetentionHours <= 0) {
            return;  // documents are retained encrypted for accountability by default
        }
        try {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT id, document FROM volunteers WHERE document IS NOT NULL AND status = 'pending' "
                    + "AND kyc_checked_at IS NOT NULL "
                    + "AND kyc_checked_at < CURRENT_TIMESTAMP - make_interval(hours => ?)", kycRetentionHours)) {
                int id = ((Number) row.get("id")).intValue();
                deleteDoc(volunteerKycDir, (String) row.get("document"));
                jdbc.update("UPDATE volunteers SET document = NULL WHERE id = ?", id);
                String msg = "Срок хранения вашего удостоверения истёк, и оно удалено. "
                    + "Загрузите документ заново, чтобы пройти верификацию.";
                jdbc.update("INSERT INTO notifications (volunteer_id, type, payload, created_at, read) "
                    + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0)", id, "kyc_doc_purged", msg);
                try {
                    telegram.notifyVolunteer(id, "⏳ " + msg);
                } catch (Exception ignore) {
                    // best-effort
                }
            }
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT n.id AS id, p.document AS document FROM needy n "
                    + "JOIN needy_profile p ON p.needy_id = n.id WHERE p.document IS NOT NULL "
                    + "AND n.status = 'pending' AND n.kyc_checked_at IS NOT NULL "
                    + "AND n.kyc_checked_at < CURRENT_TIMESTAMP - make_interval(hours => ?)", kycRetentionHours)) {
                int id = ((Number) row.get("id")).intValue();
                deleteDoc(needyUploadDir, (String) row.get("document"));
                jdbc.update("UPDATE needy_profile SET document = NULL WHERE needy_id = ?", id);
                String msg = "Срок хранения вашего документа истёк, и он удалён. "
                    + "Загрузите документ заново, чтобы подтвердить право на помощь.";
                jdbc.update("INSERT INTO notifications (needy_id, type, payload, created_at, read) "
                    + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0)", id, "kyc_doc_purged", msg);
                try {
                    telegram.notifyNeedy(id, "⏳ " + msg);
                } catch (Exception ignore) {
                    // best-effort
                }
            }
        } catch (RuntimeException e) {
            log.warning("[background] kyc_doc_retention tick failed: " + e.getMessage());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Cancel every still-open reservation on a lot leaving the витрина (§57, Q5). */
    private void cancelLotOpenTickets(int lotId, String reason) {
        List<Map<String, Object>> cancelled = jdbc.queryForList(
            "UPDATE tickets SET status = 'cancelled' WHERE lot_id = ? AND status = 'open' "
            + "RETURNING id, needy_id", lotId);
        for (Map<String, Object> row : cancelled) {
            jdbc.update("INSERT INTO notifications (needy_id, type, payload, created_at, read) "
                + "VALUES (?, ?, ?, ?, 0)", row.get("needy_id"), "ticket_cancelled",
                "Заявка #" + row.get("id") + " отменена: " + reason
                + ". Выберите другой лот — недельный лимит не потрачен.", now());
        }
    }

    /** Resolve a stored doc reference to a path INSIDE uploadDir, or null (traversal guard). */
    private static String safeDocPath(String uploadDir, String doc) {
        if (doc == null || doc.isBlank()) {
            return null;
        }
        try {
            Path base = Path.of(uploadDir).toRealPath();
            Path real = base.resolve(new File(doc).getName()).toRealPath();
            return real.startsWith(base) && Files.isRegularFile(real) ? real.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteDoc(String uploadDir, String doc) {
        String path = safeDocPath(uploadDir, doc);
        if (path != null) {
            try {
                Files.deleteIfExists(Path.of(path));
            } catch (Exception ignore) {
                // best-effort, like os.remove(OSError) pass
            }
        }
    }

    private JsonNode readJson(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String antifraudPingMessage(Integer lotId) {
        return "⚠️ Всё в порядке? Вы взяли лот #" + lotId + ", но удаляетесь от магазина. "
            + "Если планы изменились — завершите маршрут, чтобы еда вернулась на витрину. "
            + "Иначе маршрут будет снят автоматически через " + ANTIFRAUD_GRACE_MINUTES + " минут.";
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime time) {
            return time.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    /** Great-circle distance in metres (utils.py haversine). */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        return ru.savefood.util.Geo.haversineMeters(lat1, lon1, lat2, lon2);
    }
}
