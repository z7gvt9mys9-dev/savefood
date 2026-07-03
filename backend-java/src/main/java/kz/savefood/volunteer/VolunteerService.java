package kz.savefood.volunteer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import kz.savefood.gamification.Gamification;
import kz.savefood.needy.NeedyService;
import kz.savefood.security.PasswordService;
import kz.savefood.telegram.TelegramService;
import kz.savefood.util.Clamp;
import kz.savefood.util.Html;
import kz.savefood.util.JoinCode;
import kz.savefood.util.Qr;
import kz.savefood.volunteer.dto.VolunteerCreate;
import kz.savefood.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional volunteer flows from backend/volunteer/routes.py — the work the
 * Python routes did inside {@code get_db_cursor()} blocks: claiming a lot and
 * building a route (start_route), confirming a stop with server-side QR+GPS
 * verification (complete_point), delivery attempts, route finish (lot revert), and
 * corporate teams. Single-statement reads/writes live on {@link VolunteerRepository}.
 *
 * <p>The best-effort Telegram fan-out (each call wrapped in {@code try/except: pass}
 * in Python) is sent through {@link TelegramService}, which also mirrors into
 * Web Push / FCM; in-app notification rows are written here in full. The
 * transactional {@code startRoute} defers its fan-out to the controller (after
 * commit), like the enterprise webhook {@code lot.taken}.
 */
@Service
public class VolunteerService {

    // Server-side delivery verification thresholds (§13).
    static final int GPS_RADIUS_METERS = 100;
    static final int SHOP_GPS_RADIUS_METERS = 150;
    static final int PING_FRESH_SECONDS = 10 * 60;
    static final int PING_MISMATCH_METERS = 1000;
    static final int MAX_DELIVERY_ATTEMPTS = 3;
    static final int DELIVERY_WINDOW_HORIZON_MIN = 120;
    static final int ROUTE_HARD_CAP = 20;

    private static final Map<String, String[]> CAT_KEYWORDS = Map.of(
        "молочные", new String[]{"молок", "лактоз", "сыр", "творог", "кефир", "йогурт", "сметан"},
        "выпечка", new String[]{"глютен", "мука", "хлеб", "пшениц", "злак"},
        "мясо", new String[]{"мяс", "свинин", "говяд", "курин", "баранин"},
        "рыба", new String[]{"рыб", "морепродукт"},
        "орехи", new String[]{"орех", "арахис"});
    private static final String[] RESTRICTION_WORDS =
        {"аллергия", "нельзя", "не ем", "без ", "непереносимость", "не могу", "запрет"};
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[.,;\\n]+");

    static final double COARSE_GRID_DEG = 0.005;

    private final JdbcTemplate jdbc;
    private final VolunteerRepository repo;
    private final RouteRevertService routeRevert;
    private final PasswordService passwords;
    private final NeedyService needyService;
    private final TelegramService telegram;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ZoneId zone;
    private final String localTzName;

    public VolunteerService(JdbcTemplate jdbc, VolunteerRepository repo, RouteRevertService routeRevert,
                            PasswordService passwords, NeedyService needyService, TelegramService telegram,
                            @Value("${savefood.local-tz:Asia/Almaty}") String localTz) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.routeRevert = routeRevert;
        this.passwords = passwords;
        this.needyService = needyService;
        this.telegram = telegram;
        ZoneId z;
        try {
            z = ZoneId.of(localTz);
        } catch (RuntimeException e) {
            z = ZoneId.of("Asia/Almaty");
        }
        this.zone = z;
        this.localTzName = z.getId();
    }

    // ── register ─────────────────────────────────────────────────────────────────

    @Transactional
    public int registerVolunteer(VolunteerCreate vol) {
        String hashed = passwords.hash(vol.password());
        // Single transaction: a taken username rolls the volunteer row back too.
        try {
            // status='pending' explicitly (§58): the init_db grandfather (status
            // IS NULL → 'approved') must never re-approve a fresh account.
            int vid = jdbc.queryForObject(
                "INSERT INTO volunteers (name, contact, lat, lon, city, status, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 'pending', ?) RETURNING id",
                Integer.class, vol.name(), vol.contact(), vol.lat(), vol.lon(), vol.city(), OffsetDateTime.now());
            jdbc.update(
                "INSERT INTO users (username, hashed_password, role, related_id) VALUES (?, ?, 'volunteer', ?)",
                vol.username(), hashed, vid);
            return vid;
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "Username already taken");
        }
    }

    /** Carries the post-commit data the controller needs for the lot.taken webhook
     * and the Telegram fan-out ({@code assignedNeedy} = (needyId, ticketId) pairs). */
    public record StartRouteResult(int routeId, List<Map<String, Object>> points, int shopId,
                                   String lotDescription, Object lotQuantity, String volName,
                                   List<int[]> assignedNeedy) {
    }

    // ── start_route ────────────────────────────────────────────────────────────

    @Transactional
    public StartRouteResult startRoute(int volunteerId, Map<String, Object> vol, int lotId, Integer maxStops) {
        String volName = str(vol.get("name"), "volunteer_" + volunteerId);

        Map<String, Object> lot = one("SELECT * FROM lots WHERE id = ?", lotId);
        if (lot == null) {
            throw new ApiException(404, "Lot not found");
        }
        Map<String, Object> shop = one("SELECT * FROM shops WHERE id = ?", ((Number) lot.get("shop_id")).intValue());
        if (shop == null) {
            throw new ApiException(404, "Shop not found");
        }
        if (shop.get("lat") == null || shop.get("lon") == null) {
            throw new ApiException(400, "Shop has no coordinates");
        }
        // Cold chain (§47): a refrigerated lot may only be carried with a thermal bag.
        if (Boolean.TRUE.equals(lot.get("requires_cold")) && !Boolean.TRUE.equals(vol.get("has_thermal_bag"))) {
            throw new ApiException(400,
                "Этот лот требует холодильник — отметьте «есть термосумка» в профиле, чтобы взять его");
        }

        // Open delivery tickets for THIS lot only (§3.2).
        List<Map<String, Object>> tickets = jdbc.queryForList(
            "SELECT t.* FROM tickets t WHERE t.status = 'open' AND t.lat IS NOT NULL AND t.lon IS NOT NULL "
            + "AND (t.self_pickup IS NULL OR t.self_pickup = FALSE) AND t.lot_id = ?", lotId);

        boolean hadAny = !tickets.isEmpty();
        // §59/Q1-A: include recipients whose window is open now OR within the horizon.
        tickets = tickets.stream()
            .filter(t -> windowOpenWithin(str(t.get("available_time"), ""), DELIVERY_WINDOW_HORIZON_MIN))
            .collect(java.util.stream.Collectors.toList());
        if (tickets.isEmpty()) {
            throw new ApiException(400, hadAny
                ? "Все получатели этого лота недоступны в ближайшее время (вне окна доступности) — попробуйте позже"
                : "На этот лот пока нет заявок на доставку — маршрут не создан");
        }

        String lotCategory = str(lot.get("category"), "").toLowerCase();
        String[] relevantKws = CAT_KEYWORDS.entrySet().stream()
            .filter(e -> lotCategory.contains(e.getKey()))
            .map(Map.Entry::getValue).findFirst().orElse(new String[0]);

        // Pre-load profiles + scores once (the greedy loop is O(n²)).
        Map<Integer, Map<String, Object>> profiles = new LinkedHashMap<>();
        Map<Integer, Double> scores = new LinkedHashMap<>();
        for (Map<String, Object> t : tickets) {
            int nid = ((Number) t.get("needy_id")).intValue();
            profiles.computeIfAbsent(nid, this::loadProfile);
        }
        for (Map<String, Object> t : tickets) {
            scores.put(((Number) t.get("id")).intValue(), computeScore(t, profiles, relevantKws));
        }

        // §59/Q2: serve as many as fit (default = ROUTE_HARD_CAP), not a fixed 10.
        int requested = maxStops != null ? maxStops : ROUTE_HARD_CAP;
        int maxStopsResolved = Clamp.clamp(requested, 1, ROUTE_HARD_CAP);
        List<Map<String, Object>> selected = new ArrayList<>(tickets);
        selected.sort((a, b) -> Double.compare(
            scores.get(((Number) b.get("id")).intValue()), scores.get(((Number) a.get("id")).intValue())));
        if (selected.size() > maxStopsResolved) {
            selected = new ArrayList<>(selected.subList(0, maxStopsResolved));
        }
        double[] start = {num(shop.get("lat")), num(shop.get("lon"))};
        List<Map<String, Object>> order = optimizeStopOrder(selected, start);

        // Build points: shop first, then tickets.
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(point("shop", num(shop.get("lat")), num(shop.get("lon")), str(shop.get("name"), null), null, null));
        for (Map<String, Object> t : order) {
            List<String> addr = new ArrayList<>();
            if (notBlank(t.get("apartment"))) {
                addr.add("кв. " + t.get("apartment"));
            }
            if (notBlank(t.get("floor_num"))) {
                addr.add("этаж " + t.get("floor_num"));
            }
            if (notBlank(t.get("entrance"))) {
                addr.add("подъезд " + t.get("entrance"));
            }
            Map<String, Object> p = point("ticket", num(t.get("lat")), num(t.get("lon")),
                str(t.get("items"), null), ((Number) t.get("id")).intValue(),
                addr.isEmpty() ? null : String.join(", ", addr));
            p.put("apartment", t.get("apartment"));
            p.put("floor_num", t.get("floor_num"));
            p.put("entrance", t.get("entrance"));
            points.add(p);
        }

        OffsetDateTime now = OffsetDateTime.now();
        // Claim the lot (guarded), notify the shop.
        int claimed = jdbc.update(
            "UPDATE lots SET status = 'taken', taken_at = ?, taken_by = ? WHERE id = ? AND status = 'active'",
            now, volName, lotId);
        if (claimed == 0) {
            throw new ApiException(400, "Lot is not available");
        }
        jdbc.update(
            "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, ?, 0)",
            ((Number) lot.get("shop_id")).intValue(), lotId, "lot_taken",
            "Волонтёр " + volName + " забрал лот #" + lotId, now);

        // The lot leaves the shop — cancel open self-pickup tickets on it.
        for (Map<String, Object> row : jdbc.queryForList(
                "UPDATE tickets SET status = 'cancelled' WHERE lot_id = ? AND status = 'open' "
                + "AND self_pickup = TRUE RETURNING id, needy_id, quantity", lotId)) {
            returnQuantity(lotId, row.get("quantity"));
            jdbc.update(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
                ((Number) row.get("needy_id")).intValue(), "self_pickup_cancelled",
                "Заявка #" + row.get("id") + " на самовывоз отменена: лот забрал волонтёр. "
                + "Выберите другой лот — лимит не потрачен.", now);
        }

        // Assign the selected tickets (guarded against competing routes).
        List<Integer> assignedIds = new ArrayList<>();
        List<int[]> assignedNeedy = new ArrayList<>();  // (needy_id, ticket_id) — Telegram pings after commit
        for (Map<String, Object> t : order) {
            int tid = ((Number) t.get("id")).intValue();
            int rows = jdbc.update(
                "UPDATE tickets SET status = 'assigned', assigned_volunteer_id = ? WHERE id = ? AND status = 'open'",
                volunteerId, tid);
            if (rows > 0) {
                assignedIds.add(tid);
                assignedNeedy.add(new int[]{((Number) t.get("needy_id")).intValue(), tid});
                jdbc.update(
                    "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
                    ((Number) t.get("needy_id")).intValue(), "volunteer_assigned",
                    "Волонтёр " + volName + " принял вашу заявку #" + tid
                    + " и скоро поедет в магазин за продуктами", now);
            }
        }
        if (assignedIds.isEmpty()) {
            throw new ApiException(409, "Заявки уже разобраны другими волонтёрами — попробуйте другой лот");
        }

        // Remaining open delivery tickets on this (now taken) lot can never be
        // served — cancel them, return the unit (guarded no-op), bump displaced.
        for (Map<String, Object> row : jdbc.queryForList(
                "UPDATE tickets SET status = 'cancelled' WHERE lot_id = ? AND status = 'open' "
                + "AND (self_pickup IS NULL OR self_pickup = FALSE) RETURNING id, needy_id, quantity", lotId)) {
            returnQuantity(lotId, row.get("quantity"));
            jdbc.update(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
                ((Number) row.get("needy_id")).intValue(), "ticket_cancelled",
                "Заявка #" + row.get("id") + " отменена: лот забрал волонтёр для других получателей. "
                + "Выберите другой лот — недельный лимит не потрачен.", now);
            jdbc.update("UPDATE needy_profile SET displaced_count = displaced_count + 1 WHERE needy_id = ?",
                ((Number) row.get("needy_id")).intValue());
        }

        List<Map<String, Object>> filteredPoints = new ArrayList<>();
        for (Map<String, Object> p : points) {
            Object tid = p.get("ticket_id");
            if ("shop".equals(p.get("kind")) || (tid != null && assignedIds.contains(((Number) tid).intValue()))) {
                filteredPoints.add(p);
            }
        }
        String pointsJson = writeJson(filteredPoints);

        Double startDistM = null;
        if (vol.get("lat") != null && vol.get("lon") != null) {
            startDistM = haversine(num(vol.get("lat")), num(vol.get("lon")),
                num(shop.get("lat")), num(shop.get("lon"))) * 1000.0;
        }

        int routeId;
        try {
            routeId = jdbc.queryForObject(
                "INSERT INTO volunteer_routes (volunteer_id, points, status, started_at, lot_id, start_dist_m) "
                + "VALUES (?, ?, 'in_progress', ?, ?, ?) RETURNING id",
                Integer.class, volunteerId, pointsJson, now, lotId, startDistM);
        } catch (DuplicateKeyException e) {
            // uq_routes_one_active_per_volunteer: a parallel start_route won the race.
            throw new ApiException(400, "Volunteer already has an active route");
        }
        jdbc.update(
            "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
            volunteerId, "route_created",
            "Маршрут #" + routeId + " построен — посмотрите вкладку «Маршрут»", now);

        return new StartRouteResult(routeId, filteredPoints, ((Number) lot.get("shop_id")).intValue(),
            str(lot.get("description"), null), lot.get("quantity"), volName, assignedNeedy);
    }

    // ── complete_point (discrete writes, like the per-block Python cursors) ──────

    public void completePoint(Map<String, Object> route, int routeVolunteerId,
                              Integer ticketId, Double lat, Double lon, String qrCode) {
        List<Map<String, Object>> points = readPoints(route.get("points"));
        int targetIdx = -1;
        if (ticketId == null) {
            for (int i = 0; i < points.size(); i++) {
                if ("shop".equals(points.get(i).get("kind")) && !truthy(points.get(i).get("done"))) {
                    targetIdx = i;
                    break;
                }
            }
        } else {
            for (int i = 0; i < points.size(); i++) {
                Map<String, Object> p = points.get(i);
                if ("ticket".equals(p.get("kind")) && p.get("ticket_id") != null
                        && ((Number) p.get("ticket_id")).intValue() == ticketId) {
                    targetIdx = i;
                    break;
                }
            }
        }
        if (targetIdx < 0) {
            throw new ApiException(404, "Point not found in route");
        }
        Map<String, Object> point = points.get(targetIdx);
        if (truthy(point.get("done"))) {
            throw new ApiException(400, "Point already completed");
        }

        if ("ticket".equals(point.get("kind")) && point.get("ticket_id") != null) {
            int tid = ((Number) point.get("ticket_id")).intValue();
            String secret = jdbc.query("SELECT qr_secret FROM tickets WHERE id = ?",
                (rs, n) -> rs.getString("qr_secret"), tid).stream().findFirst().orElse(null);
            String expected = Qr.buildCode(tid, secret);
            if (!expected.equals(qrCode == null ? "" : qrCode.strip())) {
                throw new ApiException(400, "QR-код не совпадает с тикетом");
            }
            if (lat == null || lon == null) {
                throw new ApiException(400, "Координаты волонтёра обязательны для подтверждения доставки");
            }
            if (point.get("lat") != null && point.get("lon") != null) {
                double distM = haversine(lat, lon, num(point.get("lat")), num(point.get("lon"))) * 1000.0;
                if (distM > GPS_RADIUS_METERS) {
                    throw new ApiException(400, "Вы слишком далеко от точки доставки ("
                        + (int) distM + " м, лимит " + GPS_RADIUS_METERS + " м)");
                }
            }
            verifyPositionAgainstPings(routeVolunteerId, lat, lon);
            Map<String, Object> ticket = one("SELECT * FROM tickets WHERE id = ?", tid);
            if (ticket == null) {
                throw new ApiException(404, "Ticket not found");
            }
            int updated = jdbc.update(
                "UPDATE tickets SET status = 'fulfilled', fulfilled_at = ? "
                + "WHERE id = ? AND status = 'assigned' AND assigned_volunteer_id = ?",
                OffsetDateTime.now(), tid, routeVolunteerId);
            if (updated == 0) {
                throw new ApiException(409,
                    "Заявка уже не закреплена за вами (снята по таймауту или передана другому волонтёру)");
            }
            try {
                // §59/Q1-C: getting helped also clears displaced_count (and upserts
                // the profile row so the §3.2 weekly limit starts counting).
                needyService.setProfileLastReceived(((Number) ticket.get("needy_id")).intValue(),
                    OffsetDateTime.now());
            } catch (RuntimeException e) {
                // best-effort, like the Python try/except around set_profile_last_received
            }
        }

        if ("shop".equals(point.get("kind"))) {
            Object shopLat = point.get("lat");
            Object shopLon = point.get("lon");
            // «Я забрал» disarms the GPS drift monitor (§27), so it must itself prove presence.
            if (lat == null || lon == null) {
                throw new ApiException(400, "Координаты волонтёра обязательны для подтверждения получения лота");
            }
            if (shopLat != null && shopLon != null) {
                double distM = haversine(lat, lon, num(shopLat), num(shopLon)) * 1000.0;
                if (distM > SHOP_GPS_RADIUS_METERS) {
                    throw new ApiException(400, "Вы слишком далеко от магазина ("
                        + (int) distM + " м, лимит " + SHOP_GPS_RADIUS_METERS + " м)");
                }
            }
            verifyPositionAgainstPings(routeVolunteerId, lat, lon);
            Map<String, Object> vol = repo.getVolunteerById(routeVolunteerId);
            String volName = vol != null ? str(vol.get("name"), "volunteer_" + routeVolunteerId)
                : "volunteer_" + routeVolunteerId;
            for (Map<String, Object> p : points) {
                if (!"ticket".equals(p.get("kind")) || truthy(p.get("done")) || p.get("ticket_id") == null) {
                    continue;
                }
                Map<String, Object> t = one("SELECT * FROM tickets WHERE id = ?",
                    ((Number) p.get("ticket_id")).intValue());
                if (t == null) {
                    continue;
                }
                String status = str(t.get("status"), "");
                if (!status.equals("open") && !status.equals("assigned")) {
                    continue;
                }
                String etaText = "";
                if (shopLat != null && shopLon != null && p.get("lat") != null && p.get("lon") != null) {
                    double distKm = haversine(num(shopLat), num(shopLon), num(p.get("lat")), num(p.get("lon")));
                    int etaMin = Math.max(5, (int) (distKm / 30 * 60) + 5);
                    // Python formats the ETA off datetime.now(timezone.utc) — keep UTC for parity.
                    OffsetDateTime arrival = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(etaMin);
                    OffsetDateTime arrivalEnd = arrival.plusMinutes(30);
                    etaText = " с " + hhmm(arrival) + " до " + hhmm(arrivalEnd);
                }
                int needyId = ((Number) t.get("needy_id")).intValue();
                jdbc.update(
                    "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
                    needyId, "volunteer_en_route",
                    "Волонтёр " + volName + " едет к вам (тикет " + p.get("ticket_id")
                    + "). Ожидаемое время прибытия" + etaText + ".", OffsetDateTime.now());
                try {
                    // vol_name is user-supplied — escape before HTML parse_mode.
                    telegram.notifyNeedy(needyId, "🚗 Волонтёр " + Html.escape(volName) + " едет к вам (тикет "
                        + p.get("ticket_id") + "). Ожидаемое время прибытия" + etaText + ".");
                } catch (RuntimeException ignore) {
                    // best-effort, like the Python try/except: pass
                }
            }
        }

        point.put("done", true);
        repo.updateRoutePoints(((Number) route.get("id")).intValue(), writeJson(points));
    }

    // ── attempt_delivery ─────────────────────────────────────────────────────────

    public Map<String, Object> attemptDelivery(Map<String, Object> route, Integer ticketId) {
        List<Map<String, Object>> points = readPoints(route.get("points"));
        int targetIdx = -1;
        for (int i = 0; i < points.size(); i++) {
            Map<String, Object> p = points.get(i);
            if ("ticket".equals(p.get("kind")) && p.get("ticket_id") != null && ticketId != null
                    && ((Number) p.get("ticket_id")).intValue() == ticketId) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) {
            throw new ApiException(404, "Point not found in route");
        }
        Map<String, Object> target = points.get(targetIdx);
        if (truthy(target.get("done"))) {
            throw new ApiException(400, "Point already completed");
        }

        int attemptCount = (target.get("attempt_count") instanceof Number n ? n.intValue() : 0) + 1;
        target.put("attempt_count", attemptCount);
        boolean released = false;

        Integer needyId = jdbc.query("SELECT needy_id FROM tickets WHERE id = ?",
            (rs, n) -> (Integer) rs.getObject("needy_id"), ticketId).stream().findFirst().orElse(null);
        if (needyId != null) {
            String msg;
            if (attemptCount >= MAX_DELIVERY_ATTEMPTS) {
                jdbc.update("UPDATE tickets SET status = 'open', assigned_volunteer = NULL, "
                    + "assigned_volunteer_id = NULL WHERE id = ? AND status = 'assigned'", ticketId);
                target.put("done", true);
                target.put("released", true);
                released = true;
                msg = "Волонтёр приходил " + attemptCount + " раза, доставка не состоялась. "
                    + "Тикет возвращён в очередь — свяжитесь со службой поддержки.";
            } else {
                msg = "Волонтёр приходил, но вы не открыли дверь. Попытка #" + attemptCount
                    + ". Ожидаем звонка в течение 10 минут.";
            }
            jdbc.update(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
                needyId, "delivery_attempted", msg, OffsetDateTime.now());
            try {
                telegram.notifyNeedy(needyId, msg);
            } catch (RuntimeException ignore) {
                // best-effort, like the Python try/except: pass
            }
        }

        repo.updateRoutePoints(((Number) route.get("id")).intValue(), writeJson(points));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("attempt_count", attemptCount);
        out.put("released", released);
        return out;
    }

    // ── finish_route ─────────────────────────────────────────────────────────────

    @Transactional
    public void finishRoute(Map<String, Object> route) {
        Integer lotId = route.get("lot_id") == null ? null : ((Number) route.get("lot_id")).intValue();
        routeRevert.revertRouteLot(lotId, route.get("points") == null ? null : route.get("points").toString());
        jdbc.update("UPDATE volunteer_routes SET status = 'finished', finished_at = ? WHERE id = ?",
            OffsetDateTime.now(), ((Number) route.get("id")).intValue());
    }

    // ── Teams ──────────────────────────────────────────────────────────────────

    public Map<String, Object> teamSummary(int teamId) {
        Map<String, Object> team = one("SELECT id, name, join_code, created_at FROM teams WHERE id = ?", teamId);
        if (team == null) {
            return null;
        }
        Long members = jdbc.queryForObject(
            "SELECT COUNT(*) FROM volunteers WHERE team_id = ?", Long.class, teamId);
        Long deliveries = jdbc.queryForObject(
            "SELECT COUNT(t.id) FROM tickets t JOIN volunteers v ON v.id = t.assigned_volunteer_id "
            + "WHERE v.team_id = ? AND t.status = 'fulfilled'", Long.class, teamId);
        Double kg = jdbc.queryForObject(
            "SELECT COALESCE(SUM(l.initial_quantity * l.unit_weight_kg), 0) FROM volunteer_routes vr "
            + "JOIN lots l ON l.id = vr.lot_id JOIN volunteers v ON v.id = vr.volunteer_id "
            + "WHERE v.team_id = ? AND vr.status = 'finished'", Double.class, teamId);
        Map<String, Object> out = new LinkedHashMap<>(team);
        out.put("members", members);
        out.put("deliveries", deliveries);
        out.put("kg", kg == null ? 0.0 : kg);
        return out;
    }

    public Map<String, Object> createTeam(int volunteerId, String name) {
        // Each insert auto-commits; on the (astronomically unlikely) join-code
        // collision regenerate and retry. The volunteer UPDATE follows the
        // committed insert (the row was verified to have no team), and leaveTeam
        // GCs any empty team should that update ever fail.
        Integer teamId = null;
        for (int i = 0; i < 5 && teamId == null; i++) {
            try {
                teamId = jdbc.queryForObject(
                    "INSERT INTO teams (name, join_code) VALUES (?, ?) RETURNING id",
                    Integer.class, name.length() > 80 ? name.substring(0, 80) : name, JoinCode.generate());
            } catch (DuplicateKeyException e) {
                teamId = null;
            }
        }
        if (teamId == null) {
            throw new ApiException(500, "Не удалось создать команду, попробуйте ещё раз");
        }
        jdbc.update("UPDATE volunteers SET team_id = ? WHERE id = ?", teamId, volunteerId);
        return teamSummary(teamId);
    }

    @Transactional
    public Map<String, Object> joinTeam(int volunteerId, String code) {
        Integer teamId = jdbc.query("SELECT id FROM teams WHERE join_code = ?",
            (rs, n) -> rs.getInt("id"), code).stream().findFirst().orElse(null);
        if (teamId == null) {
            throw new ApiException(404, "Команда с таким кодом не найдена");
        }
        jdbc.update("UPDATE volunteers SET team_id = ? WHERE id = ?", teamId, volunteerId);
        return teamSummary(teamId);
    }

    @Transactional
    public void leaveTeam(int volunteerId) {
        jdbc.update("UPDATE volunteers SET team_id = NULL WHERE id = ?", volunteerId);
        jdbc.update("DELETE FROM teams t WHERE NOT EXISTS "
            + "(SELECT 1 FROM volunteers v WHERE v.team_id = t.id)");
    }

    // ── reads (map, history, rating, stats, thanks) ──────────────────────────────

    /** Volunteer map (§: shops with active lots + open delivery requests, coarsened). */
    public Map<String, Object> mapPoints() {
        Map<Integer, Map<String, Object>> shopsMap = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT s.id as shop_id, s.name, s.lat, s.lon, s.kind, l.id as lot_id, l.description, "
                + "l.quantity, l.photo, l.category FROM shops s JOIN lots l ON s.id = l.shop_id "
                + "WHERE l.status = 'active' AND l.quantity > 0 "
                + "AND (l.expiry_date IS NULL OR l.expiry_date::date > CURRENT_DATE + INTERVAL '1 day')")) {
            if (r.get("lat") == null || r.get("lon") == null) {
                continue;
            }
            int sid = ((Number) r.get("shop_id")).intValue();
            Map<String, Object> shop = shopsMap.computeIfAbsent(sid, k -> {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("shop_id", sid);
                s.put("name", r.get("name"));
                s.put("lat", r.get("lat"));
                s.put("lon", r.get("lon"));
                s.put("kind", r.get("kind") != null ? r.get("kind") : "business");
                s.put("lots", new ArrayList<Map<String, Object>>());
                return s;
            });
            Map<String, Object> lot = new LinkedHashMap<>();
            lot.put("lot_id", r.get("lot_id"));
            lot.put("description", r.get("description"));
            lot.put("quantity", r.get("quantity"));
            lot.put("photo", r.get("photo"));
            lot.put("category", r.get("category"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lots = (List<Map<String, Object>>) shop.get("lots");
            lots.add(lot);
        }

        List<Map<String, Object>> tickets = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT * FROM tickets WHERE status = 'open' AND lat IS NOT NULL AND lon IS NOT NULL "
                + "AND (self_pickup IS NULL OR self_pickup = FALSE)")) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("ticket_id", r.get("id"));
            t.put("needy_id", r.get("needy_id"));
            t.put("items", r.get("items"));
            t.put("available_time", r.get("available_time"));
            // Anti-doxxing: open requests are snapped to a ~500 m grid; the exact
            // point is disclosed only in route points after assignment.
            t.put("lat", coarsenCoord(num(r.get("lat"))));
            t.put("lon", coarsenCoord(num(r.get("lon"))));
            t.put("approx", true);
            tickets.add(t);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shops", new ArrayList<>(shopsMap.values()));
        out.put("tickets", tickets);
        return out;
    }

    public List<Map<String, Object>> historyRoutes(int volId, int limit, int offset) {
        List<Map<String, Object>> routes = repo.getRoutesByVolunteer(volId, limit, offset);
        for (Map<String, Object> r : routes) {
            r.put("points", readPoints(r.get("points")));
        }
        return routes;
    }

    public Map<String, Object> activeRoute(int volId) {
        Map<String, Object> route = repo.getActiveRoute(volId);
        if (route == null) {
            return new LinkedHashMap<>();
        }
        route.put("points", readPoints(route.get("points")));
        return route;
    }

    public Map<String, Object> ratingSummary(int volId) {
        Map<String, Object> row = one(
            "SELECT ROUND(AVG(dr.rating)::numeric, 1) as average, COUNT(*) as count "
            + "FROM delivery_ratings dr WHERE dr.volunteer_id = ?", volId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("average", row != null && row.get("average") != null ? num(row.get("average")) : null);
        out.put("count", row != null && row.get("count") != null ? ((Number) row.get("count")).intValue() : 0);
        return out;
    }

    public Map<String, Object> stats(int volId) {
        int totalRoutes = intval(jdbc.queryForObject(
            "SELECT COUNT(*) FROM volunteer_routes WHERE volunteer_id = ?", Long.class, volId));
        int totalDeliveries = intval(jdbc.queryForObject(
            "SELECT COUNT(*) FROM tickets WHERE assigned_volunteer_id = ? AND status = 'fulfilled'",
            Long.class, volId));
        double totalKg = num(jdbc.queryForObject(
            "SELECT COALESCE(SUM(l.initial_quantity * l.unit_weight_kg), 0) FROM volunteer_routes vr "
            + "JOIN lots l ON l.id = vr.lot_id WHERE vr.volunteer_id = ? AND vr.status = 'finished'",
            Double.class, volId));
        Map<String, Object> ratingRow = one(
            "SELECT ROUND(AVG(rating)::numeric, 1) as avg_rating, COUNT(*) as rating_count "
            + "FROM delivery_ratings WHERE volunteer_id = ?", volId);
        // §28 achievements.
        int nightCount = intval(jdbc.queryForObject(
            "SELECT COUNT(*) FROM tickets WHERE assigned_volunteer_id = ? AND status = 'fulfilled' "
            + "AND fulfilled_at IS NOT NULL AND EXTRACT(HOUR FROM fulfilled_at AT TIME ZONE ?) >= 20",
            Long.class, volId, localTzName));
        Map<String, Object> speedRow = one(
            "SELECT AVG(EXTRACT(EPOCH FROM (finished_at - started_at)) / 60) as avg_min, COUNT(*) as cnt "
            + "FROM volunteer_routes WHERE volunteer_id = ? AND status = 'finished' AND finished_at IS NOT NULL",
            volId);

        List<String> achievements = new ArrayList<>();
        if (totalDeliveries >= 1) {
            achievements.add("first_delivery");
        }
        if (totalKg >= 100) {
            achievements.add("kg_100");
        }
        if (nightCount > 0) {
            achievements.add("night_courier");
        }
        long speedCnt = speedRow != null && speedRow.get("cnt") != null ? ((Number) speedRow.get("cnt")).longValue() : 0;
        Object avgMin = speedRow == null ? null : speedRow.get("avg_min");
        if (speedCnt >= 3 && avgMin != null && num(avgMin) < 20) {
            achievements.add("sprinter");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_routes", totalRoutes);
        out.put("total_deliveries", totalDeliveries);
        out.put("total_kg", totalKg);
        out.put("avg_rating", ratingRow != null && ratingRow.get("avg_rating") != null ? num(ratingRow.get("avg_rating")) : null);
        out.put("rating_count", ratingRow != null && ratingRow.get("rating_count") != null
            ? ((Number) ratingRow.get("rating_count")).intValue() : 0);
        out.put("achievements", achievements);
        out.put("level", Gamification.computeLevel(totalDeliveries, totalKg));
        return out;
    }

    public List<Map<String, Object>> thanks(int volId, int limit, int offset) {
        int lim = Clamp.clamp(limit, 1, 100);
        int off = Math.max(0, offset);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT dr.rating, dr.comment, dr.created_at, l.category FROM delivery_ratings dr "
                + "JOIN tickets t ON t.id = dr.ticket_id LEFT JOIN lots l ON l.id = t.lot_id "
                + "WHERE dr.volunteer_id = ? AND dr.comment IS NOT NULL AND TRIM(dr.comment) <> '' "
                + "ORDER BY dr.created_at DESC LIMIT ? OFFSET ?", volId, lim, off)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("rating", r.get("rating"));
            e.put("comment", r.get("comment"));
            e.put("category", r.get("category"));
            Instant ts = toInstant(r.get("created_at"));
            e.put("created_at", ts == null ? null : ts.toString());
            out.add(e);
        }
        return out;
    }

    static double coarsenCoord(double value) {
        double snapped = Math.round(value / COARSE_GRID_DEG) * COARSE_GRID_DEG;
        return Math.round(snapped * 1_000_000.0) / 1_000_000.0;
    }

    private static int intval(Long v) {
        return v == null ? 0 : v.intValue();
    }

    // ── selection / scoring ──────────────────────────────────────────────────────

    private double computeScore(Map<String, Object> t, Map<Integer, Map<String, Object>> profiles, String[] kws) {
        Map<String, Object> profile = profiles.getOrDefault(((Number) t.get("needy_id")).intValue(), Map.of());
        long ageDays = daysSince(t.get("created_at"));
        // Python `profile.get('family_size') or 1`: None/0 → 1.
        long family = profile.get("family_size") instanceof Number n && n.longValue() != 0 ? n.longValue() : 1;
        int urg = switch (str(profile.get("urgency"), "").toLowerCase()) {
            case "low" -> 0;
            case "high" -> 3;
            case "critical" -> 5;
            default -> 1;
        };
        long daysNoHelp = profile.get("last_received_at") != null ? daysSince(profile.get("last_received_at")) : 0;
        long displaced = profile.get("displaced_count") instanceof Number n ? n.longValue() : 0;
        // §59/Q3 + Q1-C weighting.
        double score = ageDays * 1.5 + family * 1.0 + urg * 2.0 + daysNoHelp * 3.0 + displaced * 3.0;

        if (kws.length > 0) {
            String prefs = str(profile.get("preferences"), "").toLowerCase();
            if (!prefs.isEmpty()) {
                boolean anyClauseWithCat = false;
                boolean conflict = false;
                for (String c : CLAUSE_SPLIT.split(prefs)) {
                    String clause = c.strip();
                    if (clause.isEmpty()) {
                        continue;
                    }
                    boolean hasCat = false;
                    for (String kw : kws) {
                        if (clause.contains(kw)) {
                            hasCat = true;
                            break;
                        }
                    }
                    if (!hasCat) {
                        continue;
                    }
                    anyClauseWithCat = true;
                    for (String rw : RESTRICTION_WORDS) {
                        if (clause.contains(rw)) {
                            conflict = true;
                            break;
                        }
                    }
                }
                if (anyClauseWithCat) {
                    score += conflict ? -8.0 : 2.0;
                }
            }
        }
        return score;
    }

    private Map<String, Object> loadProfile(int needyId) {
        Map<String, Object> p = one(
            "SELECT family_size, urgency, last_received_at, displaced_count, preferences "
            + "FROM needy_profile WHERE needy_id = ?", needyId);
        return p == null ? Map.of() : p;
    }

    /**
     * §14: nearest-neighbour seed + 2-opt segment reversal until no improvement —
     * priority decides WHO is served, distance decides the visiting ORDER.
     */
    List<Map<String, Object>> optimizeStopOrder(List<Map<String, Object>> tickets, double[] start) {
        if (tickets.size() < 2) {
            return new ArrayList<>(tickets);
        }
        List<Map<String, Object>> remaining = new ArrayList<>(tickets);
        List<Map<String, Object>> order = new ArrayList<>();
        double[] cur = start;
        while (!remaining.isEmpty()) {
            Map<String, Object> best = null;
            double bestD = Double.MAX_VALUE;
            for (Map<String, Object> t : remaining) {
                double d = haversine(cur[0], cur[1], num(t.get("lat")), num(t.get("lon")));
                if (d < bestD) {
                    bestD = d;
                    best = t;
                }
            }
            order.add(best);
            remaining.remove(best);
            cur = new double[]{num(best.get("lat")), num(best.get("lon"))};
        }
        double bestLen = routeLength(order, start);
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 0; i < order.size() - 1; i++) {
                for (int j = i + 1; j < order.size(); j++) {
                    List<Map<String, Object>> cand = new ArrayList<>(order.subList(0, i));
                    List<Map<String, Object>> mid = new ArrayList<>(order.subList(i, j + 1));
                    java.util.Collections.reverse(mid);
                    cand.addAll(mid);
                    cand.addAll(order.subList(j + 1, order.size()));
                    double candLen = routeLength(cand, start);
                    if (candLen < bestLen - 1e-9) {
                        order = cand;
                        bestLen = candLen;
                        improved = true;
                    }
                }
            }
        }
        return order;
    }

    double routeLength(List<Map<String, Object>> order, double[] start) {
        double total = 0.0;
        double[] cur = start;
        for (Map<String, Object> t : order) {
            total += haversine(cur[0], cur[1], num(t.get("lat")), num(t.get("lon")));
            cur = new double[]{num(t.get("lat")), num(t.get("lon"))};
        }
        return total;
    }

    // ── geo / availability helpers ───────────────────────────────────────────────

    /** Great-circle distance in KILOMETRES (routing math works in km). */
    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        return kz.savefood.util.Geo.haversineMeters(lat1, lon1, lat2, lon2) / 1000.0;
    }

    void verifyPositionAgainstPings(int volunteerId, double lat, double lon) {
        Map<String, Object> loc = repo.getVolunteerLocation(volunteerId);
        if (loc == null || loc.get("lat") == null || loc.get("lon") == null || loc.get("updated_at") == null) {
            return;
        }
        Instant updated = toInstant(loc.get("updated_at"));
        if (updated == null) {
            return;
        }
        double age = (Instant.now().toEpochMilli() - updated.toEpochMilli()) / 1000.0;
        if (age > PING_FRESH_SECONDS) {
            return;
        }
        double distM = haversine(lat, lon, num(loc.get("lat")), num(loc.get("lon"))) * 1000.0;
        if (distM > PING_MISMATCH_METERS) {
            throw new ApiException(400, "Координаты подтверждения расходятся с вашей геолокацией на "
                + (int) distM + " м — обновите положение в приложении и попробуйте снова");
        }
    }

    /** Ticket {@code available_time} ("HH:MM-HH:MM", LOCAL_TZ) — open now? */
    boolean isAvailableNow(String availableTime) {
        return isAvailableNow(availableTime, LocalTime.now(zone));
    }

    /** Explicit-{@code now} seam so tests don't depend on the wall clock. */
    boolean isAvailableNow(String availableTime, LocalTime now) {
        if (availableTime == null || availableTime.isEmpty()) {
            return true;
        }
        try {
            String[] parts = availableTime.split("-");
            if (parts.length != 2) {
                return true;
            }
            int[] s = hm(parts[0].strip());
            int[] e = hm(parts[1].strip());
            int nowM = now.getHour() * 60 + now.getMinute();
            int startM = s[0] * 60 + s[1];
            int endM = e[0] * 60 + e[1];
            return startM <= endM ? startM <= nowM && nowM <= endM : nowM >= startM || nowM <= endM;
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** §59/Q1-A: open now OR opens within {@code horizonMinutes}. */
    boolean windowOpenWithin(String availableTime, int horizonMinutes) {
        return windowOpenWithin(availableTime, horizonMinutes, LocalTime.now(zone));
    }

    /** Explicit-{@code now} seam so tests don't depend on the wall clock. */
    boolean windowOpenWithin(String availableTime, int horizonMinutes, LocalTime now) {
        if (isAvailableNow(availableTime, now)) {
            return true;
        }
        if (availableTime == null || availableTime.isEmpty() || horizonMinutes <= 0) {
            return false;
        }
        int startM;
        try {
            int[] s = hm(availableTime.split("-")[0].strip());
            startM = s[0] * 60 + s[1];
        } catch (RuntimeException ex) {
            return true;
        }
        int nowM = now.getHour() * 60 + now.getMinute();
        int delta = Math.floorMod(startM - nowM, 24 * 60);
        return delta <= horizonMinutes;
    }

    // ── small utilities ──────────────────────────────────────────────────────────

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void returnQuantity(int lotId, Object quantity) {
        // Guarded no-op once the lot is 'taken' — reserved units must not resurrect.
        jdbc.update("UPDATE lots SET quantity = quantity + ? WHERE id = ? AND status = 'active'", quantity, lotId);
    }

    private List<Map<String, Object>> readPoints(Object pointsRaw) {
        if (pointsRaw == null) {
            return new ArrayList<>();
        }
        try {
            JsonNode node = mapper.readTree(pointsRaw.toString());
            List<Map<String, Object>> out = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode p : node) {
                    out.add(mapper.convertValue(p, Map.class));
                }
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> point(String kind, double lat, double lon, String description,
                                      Integer ticketId, String addrDetail) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", kind);
        p.put("lat", lat);
        p.put("lon", lon);
        p.put("description", description);
        p.put("ticket_id", ticketId);
        if ("ticket".equals(kind)) {
            p.put("addr_detail", addrDetail);
        }
        return p;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private long daysSince(Object ts) {
        Instant i = toInstant(ts);
        return i == null ? 0 : ChronoUnit.DAYS.between(i, Instant.now());
    }

    private static Instant toInstant(Object ts) {
        if (ts instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (ts instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        if (ts instanceof Instant i) {
            return i;
        }
        if (ts instanceof java.time.LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC);
        }
        return null;
    }

    private String hhmm(OffsetDateTime dt) {
        return String.format("%02d:%02d", dt.getHour(), dt.getMinute());
    }

    private static int[] hm(String s) {
        String[] parts = s.split(":");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : o.toString();
    }

    private static boolean truthy(Object o) {
        return Boolean.TRUE.equals(o);
    }

    private static boolean notBlank(Object o) {
        return o != null && !o.toString().isBlank();
    }
}
