package ru.savefood.volunteer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import ru.savefood.esg.EsgService;
import ru.savefood.gamification.Gamification;
import ru.savefood.needy.NeedyService;
import ru.savefood.photo.CourierProofService;
import ru.savefood.photo.DeliveryPhotoStorage;
import ru.savefood.security.PasswordService;
import ru.savefood.telegram.TelegramService;
import ru.savefood.util.Clamp;
import ru.savefood.util.FoodCategories;
import ru.savefood.util.Geo;
import ru.savefood.util.Html;
import ru.savefood.util.JoinCode;
import ru.savefood.util.Qr;
import ru.savefood.volunteer.dto.VolunteerCreate;
import ru.savefood.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
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
    static final int ROUTE_HARD_CAP = 20;
    static final int MAP_MAX_RESULTS = 100;

    static final double COARSE_GRID_DEG = 0.005;

    private final JdbcTemplate jdbc;
    private final VolunteerRepository repo;
    private final RouteRevertService routeRevert;
    private final PasswordService passwords;
    private final NeedyService needyService;
    private final TelegramService telegram;
    private final CourierProofService courierProofs;
    private final DeliveryPhotoStorage deliveryPhotos;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ZoneId zone;
    private final String localTzName;

    @Autowired
    public VolunteerService(JdbcTemplate jdbc, VolunteerRepository repo, RouteRevertService routeRevert,
                            PasswordService passwords, NeedyService needyService, TelegramService telegram,
                            CourierProofService courierProofs, DeliveryPhotoStorage deliveryPhotos,
                            @Value("${savefood.local-tz:Europe/Moscow}") String localTz) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.routeRevert = routeRevert;
        this.passwords = passwords;
        this.needyService = needyService;
        this.telegram = telegram;
        this.courierProofs = courierProofs;
        this.deliveryPhotos = deliveryPhotos;
        ZoneId z;
        try {
            z = ZoneId.of(localTz);
        } catch (RuntimeException e) {
            z = ZoneId.of("Europe/Moscow");
        }
        this.zone = z;
        this.localTzName = z.getId();
    }

    /** Constructor retained for focused unit tests without Spring/file storage. */
    public VolunteerService(JdbcTemplate jdbc, VolunteerRepository repo, RouteRevertService routeRevert,
                            PasswordService passwords, NeedyService needyService, TelegramService telegram,
                            String localTz) {
        this(jdbc, repo, routeRevert, passwords, needyService, telegram, null, null, localTz);
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

        // Lock the lot before checking its state: a direct start_route request
        // must not race a reservation/expiry transition after the UI map was read.
        Map<String, Object> lot = one("SELECT * FROM lots WHERE id = ? FOR UPDATE", lotId);
        if (lot == null) {
            throw new ApiException(404, "Lot not found");
        }
        if (!"active".equals(lot.get("status"))) {
            throw new ApiException(400, "Lot is not available");
        }
        if (!isRouteableExpiry(lot.get("expiry_date"))) {
            throw new ApiException(400, "Срок годности лота слишком близок или уже истёк");
        }
        double availableQuantity = num(lot.get("quantity"));
        if (!Double.isFinite(availableQuantity) || availableQuantity < 0) {
            throw new ApiException(400, "У лота некорректный остаток");
        }
        Map<String, Object> shop = one("SELECT * FROM shops WHERE id = ?", ((Number) lot.get("shop_id")).intValue());
        if (shop == null) {
            throw new ApiException(404, "Shop not found");
        }
        if (!Geo.isValidCoordinates(asDouble(shop.get("lat")), asDouble(shop.get("lon")))) {
            throw new ApiException(400, "Shop has no coordinates");
        }
        // Cold chain (§47): a refrigerated lot may only be carried with a thermal bag.
        if (Boolean.TRUE.equals(lot.get("requires_cold")) && !Boolean.TRUE.equals(vol.get("has_thermal_bag"))) {
            throw new ApiException(400,
                "Этот лот требует холодильник — отметьте «есть термосумка» в профиле, чтобы взять его");
        }
        // Carrying capacity (§14): a lot is claimed whole, so refuse one the
        // volunteer cannot physically carry. Undeclared capacity ⇒ no limit.
        double lotKg = lotWeightKg(lot);
        if (!Double.isFinite(lotKg) || lotKg <= 0) {
            throw new ApiException(400, "У лота некорректный вес");
        }
        if (vol.get("capacity_kg") instanceof Number capacity && capacity.doubleValue() > 0
                && lotKg > capacity.doubleValue()) {
            throw new ApiException(400, String.format(
                "Лот весит около %.0f кг — больше вашей грузоподъёмности (%.0f кг). "
                + "Измените её в профиле или выберите лот полегче.", lotKg, capacity.doubleValue()));
        }

        // Open delivery tickets for THIS lot only (§3.2).
        List<Map<String, Object>> tickets = jdbc.queryForList(
            "SELECT t.* FROM tickets t WHERE t.status = 'open' AND t.lat IS NOT NULL AND t.lon IS NOT NULL "
            + "AND (t.self_pickup IS NULL OR t.self_pickup = FALSE) AND t.lot_id = ?", lotId);

        // Historic malformed rows must never become a route point. New ticket
        // writes are range-checked at the controller, this protects old data too.
        tickets = tickets.stream()
            .filter(t -> Geo.isValidCoordinates(asDouble(t.get("lat")), asDouble(t.get("lon"))))
            .collect(java.util.stream.Collectors.toList());
        if (tickets.isEmpty()) {
            throw new ApiException(400, "На этот лот пока нет заявок на доставку — маршрут не создан");
        }

        String lotCategory = str(lot.get("category"), "");

        // Pre-load profiles + scores once (the greedy loop is O(n²)).
        Map<Integer, Map<String, Object>> profiles = new LinkedHashMap<>();
        Map<Integer, Double> scores = new LinkedHashMap<>();
        for (Map<String, Object> t : tickets) {
            int nid = ((Number) t.get("needy_id")).intValue();
            profiles.computeIfAbsent(nid, this::loadProfile);
        }
        for (Map<String, Object> t : tickets) {
            scores.put(((Number) t.get("id")).intValue(), computeScore(t, profiles, lotCategory));
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
            // Keep the recipient's actual address separate from the requested
            // products. The route is private to the assigned volunteer only.
            p.put("address", t.get("address"));
            p.put("items", t.get("items"));
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
        if (Geo.isValidCoordinates(asDouble(vol.get("lat")), asDouble(vol.get("lon")))) {
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

    @Transactional
    public void completePoint(Map<String, Object> route, int routeVolunteerId,
                              Integer ticketId, Double lat, Double lon, String qrCode) {
        route = lockActiveRoute(route, routeVolunteerId);
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
            ensurePickupCompleted(points);
            requireCoordinates(lat, lon, "Координаты волонтёра обязательны для подтверждения доставки");
            requirePointCoordinates(point, "У точки доставки некорректные координаты");
            Map<String, Object> ticket = one("SELECT * FROM tickets WHERE id = ?", tid);
            if (ticket == null) {
                throw new ApiException(404, "Ticket not found");
            }
            if (!"assigned".equals(ticket.get("status"))
                    || !(ticket.get("assigned_volunteer_id") instanceof Number assigned)
                    || assigned.intValue() != routeVolunteerId) {
                throw new ApiException(409,
                    "Заявка уже не закреплена за вами (снята по таймауту или передана другому волонтёру)");
            }
            String secret = jdbc.query("SELECT qr_secret FROM tickets WHERE id = ?",
                (rs, n) -> rs.getString("qr_secret"), tid).stream().findFirst().orElse(null);
            String expected = Qr.buildCode(tid, secret);
            if (!expected.equals(qrCode == null ? "" : qrCode.strip())) {
                // The image was captured for an invalid recipient QR. Persist the
                // cleanup in its own transaction before returning 400 so it
                // cannot be replayed on a later stop/route.
                if (courierProofs != null) {
                    courierProofs.discardForAssignedTicket(tid);
                }
                throw new ApiException(400, "QR-код не совпадает с тикетом");
            }
            double distM = haversine(lat, lon, num(point.get("lat")), num(point.get("lon"))) * 1000.0;
            if (distM > GPS_RADIUS_METERS) {
                throw new ApiException(400, "Вы слишком далеко от точки доставки ("
                    + (int) distM + " м, лимит " + GPS_RADIUS_METERS + " м)");
            }
            verifyPositionAgainstPings(routeVolunteerId, lat, lon);
            if (!hasUsableCourierProof(ticket)) {
                throw new ApiException(409,
                    "Перед подтверждением доставки загрузите фото-подтверждение");
            }
            int updated = jdbc.update(
                "UPDATE tickets SET status = 'fulfilled', fulfilled_at = ? "
                + "WHERE id = ? AND status = 'assigned' AND assigned_volunteer_id = ? "
                + "AND delivery_photo IS NOT NULL AND delivery_photo_status IN ('pending', 'approved')",
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
            requireCoordinates(lat, lon, "Координаты волонтёра обязательны для подтверждения получения лота");
            requirePointCoordinates(point, "У точки магазина некорректные координаты");
            double distM = haversine(lat, lon, num(shopLat), num(shopLon)) * 1000.0;
            if (distM > SHOP_GPS_RADIUS_METERS) {
                throw new ApiException(400, "Вы слишком далеко от магазина ("
                    + (int) distM + " м, лимит " + SHOP_GPS_RADIUS_METERS + " м)");
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
                    // LOCAL_TZ, not UTC: this string is read by a person deciding
                    // whether to be home ("с 14:00 до 15:00"). The Python original
                    // formatted it off datetime.now(timezone.utc), which in
                    // Asia/Almaty announced an arrival 5–6 hours in the past.
                    ZonedDateTime arrival = ZonedDateTime.now(zone).plusMinutes(etaMin);
                    ZonedDateTime arrivalEnd = arrival.plusMinutes(30);
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
                    telegram.notifyNeedy(needyId, "→ Волонтёр " + Html.escape(volName) + " едет к вам (тикет "
                        + p.get("ticket_id") + "). Ожидаемое время прибытия" + etaText + ".");
                } catch (RuntimeException ignore) {
                    // best-effort, like the Python try/except: pass
                }
            }
        }

        point.put("done", true);
        if ("ticket".equals(point.get("kind"))) {
            RoutePointPrivacy.redactTicketPoint(point);
        }
        repo.updateRoutePoints(((Number) route.get("id")).intValue(), writeJson(points));
    }

    // ── attempt_delivery ─────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> attemptDelivery(Map<String, Object> route, int routeVolunteerId,
                                               Integer ticketId, Double lat, Double lon) {
        route = lockActiveRoute(route, routeVolunteerId);
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
        ensurePickupCompleted(points);
        requireCoordinates(lat, lon, "Координаты волонтёра обязательны для фиксации попытки доставки");
        requirePointCoordinates(target, "У точки доставки некорректные координаты");
        double distM = haversine(lat, lon, num(target.get("lat")), num(target.get("lon"))) * 1000.0;
        if (distM > GPS_RADIUS_METERS) {
            throw new ApiException(400, "Вы слишком далеко от точки доставки ("
                + (int) distM + " м, лимит " + GPS_RADIUS_METERS + " м)");
        }
        verifyPositionAgainstPings(routeVolunteerId, lat, lon);

        Map<String, Object> ticket = one("SELECT * FROM tickets WHERE id = ?", ticketId);
        if (ticket == null) {
            throw new ApiException(404, "Ticket not found");
        }
        if (!"assigned".equals(ticket.get("status"))
                || !(ticket.get("assigned_volunteer_id") instanceof Number assigned)
                || assigned.intValue() != routeVolunteerId) {
            throw new ApiException(409,
                "Заявка уже не закреплена за вами (снята по таймауту или передана другому волонтёру)");
        }

        int attemptCount = (target.get("attempt_count") instanceof Number n ? n.intValue() : 0) + 1;
        target.put("attempt_count", attemptCount);
        boolean released = false;
        String notificationType = "delivery_attempted";

        Integer needyId = ticket.get("needy_id") instanceof Number n ? n.intValue() : null;
        if (needyId != null) {
            String msg;
            if (attemptCount >= MAX_DELIVERY_ATTEMPTS) {
                // Read the old reference under a row lock before clearing it:
                // UPDATE ... RETURNING would only expose the new NULL value.
                List<Map<String, Object>> locked = jdbc.queryForList(
                    "SELECT delivery_photo FROM tickets WHERE id = ? AND status = 'assigned' "
                        + "AND assigned_volunteer_id = ? FOR UPDATE", ticketId, routeVolunteerId);
                if (locked.isEmpty()) {
                    throw new ApiException(409,
                        "Заявка уже не закреплена за вами (снята по таймауту или передана другому волонтёру)");
                }
                String proof = locked.get(0).get("delivery_photo") instanceof String s ? s : null;
                // Pickup was verified above: the food is no longer at the shop,
                // so terminal failure cancels this reservation without restoring stock.
                int releasedRows = jdbc.update("UPDATE tickets SET status = 'cancelled', assigned_volunteer = NULL, "
                    + "assigned_volunteer_id = NULL, delivery_photo = NULL, delivery_photo_status = NULL, "
                    + "delivery_photo_ai_verdict = NULL, delivery_photo_ai_score = NULL, "
                    + "delivery_photo_ai_notes = NULL, delivery_photo_reviewed_at = NULL "
                    + "WHERE id = ? AND status = 'assigned' AND assigned_volunteer_id = ?", ticketId, routeVolunteerId);
                if (releasedRows == 0) {
                    throw new ApiException(409,
                        "Заявка уже не закреплена за вами (снята по таймауту или передана другому волонтёру)");
                }
                if (proof != null && deliveryPhotos != null) {
                    deliveryPhotos.deleteAfterCommit(proof);
                }
                target.put("done", true);
                target.put("cancelled", true);
                RoutePointPrivacy.redactTicketPoint(target);
                released = true;
                notificationType = "ticket_cancelled";
                msg = "Волонтёр приходил " + attemptCount + " раза, доставка не состоялась. "
                    + "Заявка отменена — выберите другой лот, недельный лимит не потрачен.";
            } else {
                msg = "Волонтёр приходил, но вы не открыли дверь. Попытка #" + attemptCount
                    + ". Ожидаем звонка в течение 10 минут.";
            }
            jdbc.update(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
                needyId, notificationType, msg, OffsetDateTime.now());
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

    /**
     * Attach a courier's delivery proof before QR completion. The file is saved by
     * the controller first, then this guarded update binds it only to the active
     * route's still-assigned stop. Returns the replaced private photo reference.
     */
    @Transactional
    public String attachDeliveryPhoto(Map<String, Object> route, int routeVolunteerId,
                                      int ticketId, Double lat, Double lon, String photoRef) {
        route = lockActiveRoute(route, routeVolunteerId);
        List<Map<String, Object>> points = readPoints(route.get("points"));
        ensurePickupCompleted(points);
        boolean targetFound = false;
        for (Map<String, Object> point : points) {
            if ("ticket".equals(point.get("kind")) && point.get("ticket_id") instanceof Number id
                    && id.intValue() == ticketId) {
                if (truthy(point.get("done"))) {
                    throw new ApiException(400, "Точка доставки уже завершена");
                }
                targetFound = true;
                break;
            }
        }
        if (!targetFound) {
            throw new ApiException(404, "Point not found in route");
        }
        Map<String, Object> target = points.stream()
            .filter(p -> "ticket".equals(p.get("kind")) && p.get("ticket_id") instanceof Number id
                && id.intValue() == ticketId)
            .findFirst().orElseThrow(() -> new ApiException(404, "Point not found in route"));
        requireCoordinates(lat, lon, "Координаты волонтёра обязательны для загрузки фото доставки");
        requirePointCoordinates(target, "У точки доставки некорректные координаты");
        double distM = haversine(lat, lon, num(target.get("lat")), num(target.get("lon"))) * 1000.0;
        if (distM > GPS_RADIUS_METERS) {
            throw new ApiException(400, "Вы слишком далеко от точки доставки ("
                + (int) distM + " м, лимит " + GPS_RADIUS_METERS + " м)");
        }
        verifyPositionAgainstPings(routeVolunteerId, lat, lon);
        Map<String, Object> ticket = one("SELECT * FROM tickets WHERE id = ?", ticketId);
        if (ticket == null) {
            throw new ApiException(404, "Ticket not found");
        }
        if (!"assigned".equals(ticket.get("status"))
                || !(ticket.get("assigned_volunteer_id") instanceof Number assigned)
                || assigned.intValue() != routeVolunteerId) {
            throw new ApiException(409,
                "Заявка уже не закреплена за вами (снята по таймауту или передана другому волонтёру)");
        }
        int rows = jdbc.update(
            "UPDATE tickets SET delivery_photo = ?, delivery_photo_status = 'pending', "
            + "delivery_photo_ai_verdict = NULL, delivery_photo_ai_score = NULL, "
            + "delivery_photo_ai_notes = NULL, delivery_photo_reviewed_at = NULL "
            + "WHERE id = ? AND status = 'assigned' AND assigned_volunteer_id = ?",
            photoRef, ticketId, routeVolunteerId);
        if (rows == 0) {
            throw new ApiException(409,
                "Заявка уже не закреплена за вами (снята по таймауту или передана другому волонтёру)");
        }
        String previous = (String) ticket.get("delivery_photo");
        if (previous != null && !previous.equals(photoRef) && deliveryPhotos != null) {
            deliveryPhotos.deleteAfterCommit(previous);
        }
        return previous;
    }

    // ── finish_route ─────────────────────────────────────────────────────────────

    @Transactional
    public void finishRoute(Map<String, Object> route) {
        int volunteerId = ((Number) route.get("volunteer_id")).intValue();
        route = lockActiveRoute(route, volunteerId);
        Integer lotId = route.get("lot_id") == null ? null : ((Number) route.get("lot_id")).intValue();
        routeRevert.revertRouteLot(lotId, route.get("points") == null ? null : route.get("points").toString());
        jdbc.update("UPDATE volunteer_routes SET points = ?, status = 'finished', finished_at = ? WHERE id = ?",
            RoutePointPrivacy.redactAllTicketPointsJson(route.get("points")), OffsetDateTime.now(),
            ((Number) route.get("id")).intValue());
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
            "SELECT " + EsgService.FULFILLED_TICKET_KG_SQL + " FROM tickets t "
            + "JOIN lots l ON l.id = t.lot_id JOIN volunteers v ON v.id = t.assigned_volunteer_id "
            + "WHERE v.team_id = ? AND t.status = 'fulfilled'", Double.class, teamId);
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

    /**
     * Volunteer map for one city. Both collections are capped and every ticket
     * query is constrained to the same bounded set of lots.
     */
    public Map<String, Object> mapPoints(String city, int requestedLimit) {
        int limit = Math.min(requestedLimit, MAP_MAX_RESULTS);
        String mapLots = "WITH map_lots AS ("
            + "SELECT s.id AS shop_id, s.name, s.lat, s.lon, s.kind, l.id AS lot_id, l.description, "
            + "l.quantity, l.photo, l.category, COUNT(t.id) AS open_delivery_tickets "
            + "FROM shops s JOIN lots l ON s.id = l.shop_id "
            + "JOIN tickets t ON t.lot_id = l.id "
            + "WHERE s.city = ? AND s.lat BETWEEN -90 AND 90 AND s.lon BETWEEN -180 AND 180 "
            + "AND l.status = 'active' "
            + "AND (l.expiry_date IS NULL OR l.expiry_date::date > CURRENT_DATE + INTERVAL '1 day') "
            + "AND t.status = 'open' AND t.lat BETWEEN -90 AND 90 AND t.lon BETWEEN -180 AND 180 "
            + "AND (t.self_pickup IS NULL OR t.self_pickup = FALSE) "
            + "GROUP BY s.id, s.name, s.lat, s.lon, s.kind, l.id, l.description, l.quantity, l.photo, l.category "
            + "ORDER BY l.id LIMIT ?) ";
        Map<Integer, Map<String, Object>> shopsMap = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(mapLots + "SELECT * FROM map_lots", city, limit)) {
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
            int openDeliveryTickets = r.get("open_delivery_tickets") instanceof Number n ? n.intValue() : 0;
            // This is a delivery-only view: every returned lot already has a
            // recipient reservation, even if stock remains for more recipients.
            lot.put("status", "reserved");
            lot.put("open_delivery_tickets", openDeliveryTickets);
            lot.put("route_available", true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lots = (List<Map<String, Object>>) shop.get("lots");
            lots.add(lot);
        }

        List<Map<String, Object>> tickets = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(mapLots
            + "SELECT t.*, l.description AS lot_description, l.quantity AS lot_quantity, l.photo AS lot_photo, "
            + "l.category AS lot_category, l.status AS lot_status, s.id AS shop_id, s.name AS shop_name, "
            + "s.lat AS shop_lat, s.lon AS shop_lon, s.kind AS shop_kind "
            + "FROM tickets t JOIN map_lots ml ON ml.lot_id = t.lot_id "
            + "JOIN lots l ON l.id = t.lot_id JOIN shops s ON s.id = l.shop_id "
            + "WHERE t.status = 'open' AND t.lat BETWEEN -90 AND 90 AND t.lon BETWEEN -180 AND 180 "
            + "AND (t.self_pickup IS NULL OR t.self_pickup = FALSE) ORDER BY t.id LIMIT ?", city, limit, limit)) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("ticket_id", r.get("id"));
            t.put("lot_id", r.get("lot_id"));
            t.put("needy_id", r.get("needy_id"));
            t.put("items", r.get("items"));
            t.put("available_time", r.get("available_time"));
            // Anti-doxxing: open requests are snapped to a ~500 m grid; the exact
            // point is disclosed only in route points after assignment.
            t.put("lat", coarsenCoord(num(r.get("lat"))));
            t.put("lon", coarsenCoord(num(r.get("lon"))));
            t.put("approx", true);
            t.put("route_available", r.get("lot_id") != null);
            // A reserved last unit has no public /lots card. Include the minimal
            // lot + shop card here so mobile/web clients can still call
            // start_route(lot_id) from its coarsened ticket marker.
            t.put("lot_description", r.get("lot_description"));
            t.put("lot_quantity", r.get("lot_quantity"));
            t.put("lot_photo", r.get("lot_photo"));
            t.put("lot_category", r.get("lot_category"));
            t.put("lot_status", r.get("lot_status"));
            t.put("shop_id", r.get("shop_id"));
            t.put("shop_name", r.get("shop_name"));
            t.put("shop_lat", r.get("shop_lat"));
            t.put("shop_lon", r.get("shop_lon"));
            t.put("shop_kind", r.get("shop_kind"));
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
            List<Map<String, Object>> points = readPoints(r.get("points"));
            // History is not an operational delivery surface. Always redact its
            // ticket points, including active/legacy rows, as defense in depth.
            RoutePointPrivacy.redactAllTicketPoints(points);
            r.put("points", points);
        }
        return routes;
    }

    public Map<String, Object> activeRoute(int volId, boolean exposeAssignedRecipientData) {
        Map<String, Object> route = repo.getActiveRoute(volId);
        if (route == null) {
            return new LinkedHashMap<>();
        }
        List<Map<String, Object>> points = readPoints(route.get("points"));
        if (!exposeAssignedRecipientData) {
            RoutePointPrivacy.redactAllTicketPoints(points);
        } else {
            List<Integer> assignedTicketIds = jdbc.query(
                "SELECT id FROM tickets WHERE assigned_volunteer_id = ? AND status = 'assigned'",
                (rs, rowNum) -> rs.getInt("id"), volId);
            for (Map<String, Object> point : points) {
                Object ticketId = point.get("ticket_id");
                if ("ticket".equals(point.get("kind"))
                        && (!(ticketId instanceof Number id) || !assignedTicketIds.contains(id.intValue()))) {
                    RoutePointPrivacy.redactTicketPoint(point);
                }
            }
        }
        route.put("points", points);
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
            "SELECT " + EsgService.FULFILLED_TICKET_KG_SQL + " FROM tickets t "
            + "JOIN lots l ON l.id = t.lot_id "
            + "WHERE t.assigned_volunteer_id = ? AND t.status = 'fulfilled'",
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

    /**
     * Priority of one ticket when the lot cannot serve everyone (§6).
     *
     * <p>All terms are facts the platform can verify: how long the request has
     * waited, how many mouths it feeds, how long since this recipient last got
     * help, and how often they have already been displaced by someone else's
     * claim. Self-declared urgency used to add up to +10 here and was dropped
     * deliberately — unverifiable, so the rational move was to always claim
     * "critical", which made the field a constant for strategic users and a
     * penalty for honest ones. It is still stored and shown, just not ranked on.
     */
    private double computeScore(Map<String, Object> t, Map<Integer, Map<String, Object>> profiles,
                                String lotCategory) {
        Map<String, Object> profile = profiles.getOrDefault(((Number) t.get("needy_id")).intValue(), Map.of());
        long ageDays = daysSince(t.get("created_at"));
        // Python `profile.get('family_size') or 1`: None/0 → 1.
        long family = profile.get("family_size") instanceof Number n && n.longValue() != 0 ? n.longValue() : 1;
        long daysNoHelp = profile.get("last_received_at") != null ? daysSince(profile.get("last_received_at")) : 0;
        long displaced = profile.get("displaced_count") instanceof Number n ? n.longValue() : 0;
        // §59/Q3 + Q1-C weighting.
        double score = ageDays * 1.5 + family * 1.0 + daysNoHelp * 3.0 + displaced * 3.0;

        return score + switch (FoodCategories.preferenceSignal(lotCategory, str(profile.get("preferences"), ""))) {
            case CONFLICT -> -8.0;
            case MATCH -> 2.0;
            case NEUTRAL -> 0.0;
        };
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
        return ru.savefood.util.Geo.haversineMeters(lat1, lon1, lat2, lon2) / 1000.0;
    }

    void verifyPositionAgainstPings(int volunteerId, double lat, double lon) {
        requireCoordinates(lat, lon, "Некорректные координаты подтверждения");
        Map<String, Object> loc = repo.getVolunteerLocation(volunteerId);
        if (loc == null || loc.get("lat") == null || loc.get("lon") == null || loc.get("updated_at") == null) {
            return;
        }
        Instant updated = toInstant(loc.get("updated_at"));
        if (updated == null) {
            return;
        }
        if (!Geo.isValidCoordinates(asDouble(loc.get("lat")), asDouble(loc.get("lon")))) {
            // Legacy bad pings cannot prove anything; new pings are rejected at
            // both Java and Go ingress, so treating it as absent is safest.
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

    // ── small utilities ──────────────────────────────────────────────────────────

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Controllers read a route to authorize its owner, but that snapshot can go
     * stale while another request finishes/resets it. Lifecycle mutations must
     * operate on a locked, freshly rechecked row to avoid lost point updates.
     */
    private Map<String, Object> lockActiveRoute(Map<String, Object> staleRoute, int expectedVolunteerId) {
        if (!(staleRoute.get("id") instanceof Number id)) {
            throw new ApiException(404, "Route not found");
        }
        Map<String, Object> route = repo.getRouteByIdForUpdate(id.intValue());
        if (route == null) {
            throw new ApiException(404, "Route not found");
        }
        if (!(route.get("volunteer_id") instanceof Number owner) || owner.intValue() != expectedVolunteerId) {
            throw new ApiException(409, "Маршрут уже изменился — обновите страницу");
        }
        if (!"in_progress".equals(route.get("status"))) {
            throw new ApiException(409, "Маршрут уже завершён или сброшен");
        }
        return route;
    }

    /**
     * Weight of the whole lot in kg — what the volunteer actually has to carry.
     * Uses {@code initial_quantity} rather than the live {@code quantity}: the
     * latter is already reduced by outstanding reservations, but the volunteer
     * still picks up every unit of the lot.
     */
    static double lotWeightKg(Map<String, Object> lot) {
        Object initial = lot.get("initial_quantity");
        double units = num(initial != null ? initial : lot.get("quantity"));
        Object perUnit = lot.get("unit_weight_kg");
        double weight = perUnit instanceof Number n ? n.doubleValue() : 1.0;
        return units * (weight > 0 ? weight : 1.0);
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

    private String hhmm(ZonedDateTime dt) {
        return String.format("%02d:%02d", dt.getHour(), dt.getMinute());
    }

    private static int[] hm(String s) {
        String[] parts = s.split(":");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    /** A lot may have quantity 0 only when its already-reserved open tickets are served. */
    private static boolean isRouteableExpiry(Object value) {
        if (value == null) {
            return true;
        }
        LocalDate expiry = toLocalDate(value);
        return expiry != null && expiry.isAfter(LocalDate.now().plusDays(1));
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDate();
        }
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.toString().substring(0, 10));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void requireCoordinates(Double lat, Double lon, String message) {
        if (!Geo.isValidCoordinates(lat, lon)) {
            throw new ApiException(400, message + " (широта -90..90, долгота -180..180)");
        }
    }

    private static void requirePointCoordinates(Map<String, Object> point, String message) {
        requireCoordinates(asDouble(point.get("lat")), asDouble(point.get("lon")), message);
    }

    private static boolean hasUsableCourierProof(Map<String, Object> ticket) {
        if (!(ticket.get("delivery_photo") instanceof String photo) || photo.isBlank()) {
            return false;
        }
        String status = ticket.get("delivery_photo_status") instanceof String s ? s : "";
        return "pending".equals(status) || "approved".equals(status);
    }

    static void ensurePickupCompleted(List<Map<String, Object>> points) {
        for (Map<String, Object> point : points) {
            if ("shop".equals(point.get("kind"))) {
                if (!truthy(point.get("done"))) {
                    throw new ApiException(409,
                        "Сначала подтвердите получение лота в магазине");
                }
                return;
            }
        }
        throw new ApiException(409, "Маршрут не содержит точки получения лота");
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
