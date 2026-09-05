package ru.savefood.background;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import ru.savefood.kyc.KycService;
import ru.savefood.storage.SensitiveFileCleanup;
import ru.savefood.storage.SensitiveFileCleanup.Storage;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.RoutePointPrivacy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class MaintenanceTasks {
    private static final Logger log = Logger.getLogger(MaintenanceTasks.class.getName());
    private static final int REASSIGN_TIMEOUT_MINUTES = 90;
    private static final int MAX_ROUTE_DURATION_MINUTES = 240;
    private static final int ANTIFRAUD_CHECK_AFTER_MINUTES = 15;
    private static final int ANTIFRAUD_GRACE_MINUTES = 15;
    private static final double ANTIFRAUD_DRIFT_THRESHOLD_M = 300;
    private static final int DEFAULT_RESERVATION_TTL_BATCH = 100;
    private static final int MAX_RESERVATION_TTL_BATCH = 200;
    private record AntifraudAction(String kind, int volunteerId, Integer lotId) {
    }
    private record ReservationExpiryNotification(int needyId, String message) {
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RouteRevertService revert;
    private final KycService kyc;
    private final TelegramService telegram;
    private final SensitiveFileCleanup sensitiveFiles;
    private final boolean enabled;
    private final String supportChatId;
    private final String volunteerKycDir;
    private final int kycRetryBatch;
    private final int kycRetentionHours;
    private final int reservationTtlBatch;
    @Autowired
    public MaintenanceTasks(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                            RouteRevertService revert, KycService kyc, TelegramService telegram,
                            SensitiveFileCleanup sensitiveFiles,
                            @Value("${savefood.background-tasks:off}") String mode,
                            @Value("${savefood.support-chat-id:}") String supportChatId,
                            @Value("${savefood.volunteer-kyc-upload-dir:../backend/volunteer/kyc_uploads}") String volunteerKycDir,
                            @Value("${savefood.kyc-retry-batch:100}") int kycRetryBatch,
                            @Value("${savefood.kyc-doc-retention-hours:0}") int kycRetentionHours,
                            @Value("${savefood.reservation-ttl-batch-size:100}") int reservationTtlBatch) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.revert = revert;
        this.kyc = kyc;
        this.telegram = telegram;
        this.sensitiveFiles = sensitiveFiles;
        this.enabled = "embedded".equalsIgnoreCase(mode.strip());
        this.supportChatId = supportChatId == null ? "" : supportChatId;
        this.volunteerKycDir = volunteerKycDir;
        this.kycRetryBatch = kycRetryBatch;
        this.kycRetentionHours = kycRetentionHours;
        this.reservationTtlBatch = Math.max(1, Math.min(reservationTtlBatch, MAX_RESERVATION_TTL_BATCH));
    }
    /** Constructor retained for focused tests unrelated to filesystem cleanup. */
    public MaintenanceTasks(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                            RouteRevertService revert, KycService kyc, TelegramService telegram,
                            String mode, String supportChatId, String volunteerKycDir,
                            int kycRetryBatch, int kycRetentionHours) {
        this(jdbc, txManager, revert, kyc, telegram, null, mode, supportChatId,
            volunteerKycDir, kycRetryBatch, kycRetentionHours, DEFAULT_RESERVATION_TTL_BATCH);
    }
    /** Constructor with an explicit reservation batch size for focused tests. */
    public MaintenanceTasks(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                            RouteRevertService revert, KycService kyc, TelegramService telegram,
                            String mode, String supportChatId, String volunteerKycDir,
                            int kycRetryBatch, int kycRetentionHours, int reservationTtlBatch) {
        this(jdbc, txManager, revert, kyc, telegram, null, mode, supportChatId,
            volunteerKycDir, kycRetryBatch, kycRetentionHours, reservationTtlBatch);
    }
    @Scheduled(fixedDelay = 30 * 60_000, initialDelay = 60_000)
    public void expireTick() {
        if (!enabled) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM lots WHERE status = 'active' AND expiry_date IS NOT NULL "
                + "AND expiry_date <= CURRENT_DATE + INTERVAL '1 day'");
            int n = 0;
            for (Map<String, Object> r : rows) {
                int lotId = ((Number) r.get("id")).intValue();
                try {
                    Boolean expired = tx.execute(s -> {
                        List<Integer> shopIds = jdbc.query(
                            "UPDATE lots SET status = 'expired' WHERE id = ? AND status = 'active' "
                            + "AND expiry_date IS NOT NULL "
                            + "AND expiry_date <= CURRENT_DATE + INTERVAL '1 day' RETURNING shop_id",
                            (rs, rowNum) -> rs.getInt("shop_id"), lotId);
                        if (shopIds.isEmpty()) {
                            return false;
                        }
                        jdbc.update(
                            "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) "
                            + "VALUES (?, ?, ?, ?, ?, 0)",
                            shopIds.get(0), lotId, "lot_expired_soon",
                            "Лот #" + lotId + " снят: до истечения срока годности менее 24 часов", now());
                        cancelLotOpenTickets(lotId, "лоту осталось менее 24 часов до истечения срока");
                        return true;
                    });
                    if (Boolean.TRUE.equals(expired)) {
                        n++;
                    }
                } catch (RuntimeException ignore) {
                }
            }
            if (n > 0) {
                log.info("[background] expire_tick: " + n + " lots expired");
            }
        } catch (RuntimeException e) {
            log.warning("[background] expire tick failed: " + e.getMessage());
        }
    }
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
                Map<String, Object> timedOutRoute;
                try {
                    timedOutRoute = tx.execute(s -> {
                        revert.lockRouteForRevert(routeId);
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
                        int updated = jdbc.update("UPDATE volunteer_routes SET points = ?, status = 'timed_out', "
                            + "finished_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'in_progress'",
                            RoutePointPrivacy.redactAllTicketPointsJson(points), routeId);
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
                        telegram.sendMessage(supportChatId, "! Маршрут #" + routeId + " волонтёра "
                            + timedOutRoute.get("volunteer_id") + " переназначен по таймауту.");
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warning("[background] reassign tick failed: " + e.getMessage());
        }
    }
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
            revert.lockRouteForRevert(routeId);
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
                return null;
            }
            revert.revertRouteLot(lotId, pointsJson);
            int updated = jdbc.update("UPDATE volunteer_routes SET points = ?, status = 'timed_out', "
                + "finished_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'in_progress'",
                RoutePointPrivacy.redactAllTicketPointsJson(pointsJson), routeId);
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
            }
            return;
        }
        if (!supportChatId.isEmpty()) {
            try {
                telegram.sendMessage(supportChatId, "! Антифрод: маршрут #" + routeId + " волонтёра "
                    + action.volunteerId() + " снят (удалялся от магазина, лот #" + action.lotId()
                    + " возвращён).");
            } catch (Exception ignore) {
            }
        }
    }
    @Scheduled(fixedDelay = 5 * 60_000, initialDelay = 150_000)
    public void reservationTtlTick() {
        if (!enabled) {
            return;
        }
        List<ReservationExpiryNotification> deliveries;
        try {
            deliveries = tx.execute(s -> {
                List<Integer> lotIds = jdbc.query(
                    "SELECT l.id FROM lots l WHERE EXISTS (SELECT 1 FROM tickets t "
                    + "WHERE t.lot_id = l.id AND t.status = 'open' "
                    + "AND t.expires_at IS NOT NULL AND t.expires_at < CURRENT_TIMESTAMP "
                    + "AND t.assigned_volunteer_id IS NULL AND t.assigned_volunteer IS NULL) "
                    + "ORDER BY l.id LIMIT ? FOR UPDATE OF l SKIP LOCKED",
                    (rs, rowNum) -> rs.getInt("id"), reservationTtlBatch);
                if (lotIds.isEmpty()) {
                    return List.of();
                }
                String lotPlaceholders = String.join(",", java.util.Collections.nCopies(lotIds.size(), "?"));
                List<Map<String, Object>> expired = jdbc.queryForList(
                    "SELECT id, needy_id, lot_id, quantity, self_pickup FROM tickets "
                    + "WHERE lot_id IN (" + lotPlaceholders + ") AND status = 'open' "
                    + "AND expires_at IS NOT NULL AND expires_at < CURRENT_TIMESTAMP "
                    + "AND assigned_volunteer_id IS NULL AND assigned_volunteer IS NULL "
                    + "ORDER BY expires_at, id LIMIT " + reservationTtlBatch
                    + " FOR UPDATE SKIP LOCKED", lotIds.toArray());
                List<ReservationExpiryNotification> pendingDeliveries = new ArrayList<>(expired.size());
                for (Map<String, Object> t : expired) {
                    int id = ((Number) t.get("id")).intValue();
                    Integer needyId = t.get("needy_id") == null ? null : ((Number) t.get("needy_id")).intValue();
                    Integer lotId = t.get("lot_id") == null ? null : ((Number) t.get("lot_id")).intValue();
                    Number quantity = (Number) t.get("quantity");
                    boolean selfPickup = Boolean.TRUE.equals(t.get("self_pickup"));
                    int cancelled = jdbc.update("UPDATE tickets SET status = 'cancelled' WHERE id = ? "
                        + "AND status = 'open' AND assigned_volunteer_id IS NULL "
                        + "AND assigned_volunteer IS NULL", id);
                    if (cancelled != 1) {
                        continue;
                    }
                    if (lotId != null) {
                        jdbc.update("UPDATE lots SET quantity = quantity + ? WHERE id = ? AND status = 'active'",
                            quantity, lotId);
                    }
                    String msg = selfPickup
                        ? "◷ Срок брони лота #" + lotId + " (самовывоз) истёк. Заявка отменена, "
                          + "еда вернулась на витрину."
                        : "◷ Бронь лота #" + lotId + " истекла: волонтёр не взялся за доставку. "
                          + "Заявка отменена, еда вернулась на витрину — лимит не потрачен.";
                    String ntype = selfPickup ? "self_pickup_expired" : "reservation_expired";
                    jdbc.update("INSERT INTO notifications (needy_id, type, payload, created_at, read) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0)", needyId, ntype, msg);
                    if (needyId != null) {
                        pendingDeliveries.add(new ReservationExpiryNotification(needyId, "◷ " + msg));
                    }
                }
                return pendingDeliveries;
            });
        } catch (RuntimeException e) {
            log.warning("[background] reservation_ttl tick failed: " + e.getMessage());
            return;
        }
        for (ReservationExpiryNotification delivery : deliveries) {
            try {
                telegram.notifyNeedy(delivery.needyId(), delivery.message());
            } catch (Exception ignore) {
            }
        }
    }
    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 180_000)
    public void kycRetryTick() {
        if (!enabled) {
            return;
        }
        try {
            List<Map<String, Object>> vols = jdbc.queryForList(
                "SELECT id, name, document, kyc_generation FROM volunteers "
                + "WHERE status = 'pending' AND document IS NOT NULL AND kyc_generation IS NOT NULL "
                + "AND (kyc_verdict IS NULL OR kyc_verdict = 'unchecked') "
                + "ORDER BY kyc_checked_at ASC NULLS FIRST LIMIT ?", kycRetryBatch);
            for (Map<String, Object> v : vols) {
                String path = safeDocPath(volunteerKycDir, (String) v.get("document"));
                if (path != null) {
                    kyc.startVolunteerKycCheck(((Number) v.get("id")).intValue(), path,
                        v.get("name") == null ? "" : v.get("name").toString(),
                        (String) v.get("kyc_generation"));
                }
            }
        } catch (RuntimeException e) {
            log.warning("[background] kyc_retry tick failed: " + e.getMessage());
        }
    }
    @Scheduled(fixedDelay = 60 * 60_000, initialDelay = 300_000)
    public void kycDocRetentionTick() {
        if (!enabled || kycRetentionHours <= 0) {
            return;
        }
        try {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT id, document, kyc_generation FROM volunteers "
                    + "WHERE document IS NOT NULL AND kyc_generation IS NOT NULL AND status = 'pending' "
                    + "AND kyc_checked_at IS NOT NULL "
                    + "AND kyc_checked_at < CURRENT_TIMESTAMP - make_interval(hours => ?)", kycRetentionHours)) {
                purgeVolunteerKycDocument(((Number) row.get("id")).intValue(),
                    (String) row.get("document"), (String) row.get("kyc_generation"));
            }
        } catch (RuntimeException e) {
            log.warning("[background] kyc_doc_retention tick failed: " + e.getMessage());
        }
    }
    /** Guarded winner step for one retention candidate, kept visible for focused tests. */
    boolean purgeVolunteerKycDocument(int id, String document, String generation) {
        return Boolean.TRUE.equals(tx.execute(status ->
            purgeVolunteerKycDocumentInTransaction(id, document, generation)));
    }
    private boolean purgeVolunteerKycDocumentInTransaction(int id, String document, String generation) {
        int won = jdbc.update(
            "UPDATE volunteers SET document = NULL, kyc_generation = NULL "
            + "WHERE id = ? AND document = ? AND kyc_generation = ? AND status = 'pending' "
            + "AND kyc_checked_at IS NOT NULL "
            + "AND kyc_checked_at < CURRENT_TIMESTAMP - make_interval(hours => ?)",
            id, document, generation, kycRetentionHours);
        if (won != 1) {
            return false;
        }
        if (sensitiveFiles == null) {
            throw new IllegalStateException("Sensitive-file cleanup is required for KYC retention");
        }
        sensitiveFiles.trackAndDeleteAfterCommit(Storage.VOLUNTEER_KYC, document);
        String msg = "Срок хранения вашего удостоверения истёк, и оно удалено. "
            + "Загрузите документ заново, чтобы пройти верификацию.";
        jdbc.update("INSERT INTO notifications (volunteer_id, type, payload, created_at, read) "
            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0)", id, "kyc_doc_purged", msg);
        try {
            telegram.notifyVolunteer(id, "◷ " + msg);
        } catch (Exception ignore) {
        }
        return true;
    }
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
        return "! Всё в порядке? Вы взяли лот #" + lotId + ", но удаляетесь от магазина. "
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
