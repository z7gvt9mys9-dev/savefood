package ru.savefood.needy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.savefood.photo.DeliveryPhotoStorage;
import ru.savefood.security.PasswordService;
import ru.savefood.util.Qr;
import ru.savefood.volunteer.RoutePointPrivacy;
import ru.savefood.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class NeedyService {
    /** Reservation failure reasons, mapped to user messages by the controller (routes.py {@code create_ticket}). */
    public static class TicketCreateException extends RuntimeException {
        private final String reason;
        public TicketCreateException(String reason) {
            super(reason);
            this.reason = reason;
        }
        public String reason() {
            return reason;
        }
    }
    private final JdbcTemplate jdbc;
    private final NeedyRepository repo;
    private final PasswordService passwords;
    private final DeliveryPhotoStorage deliveryPhotos;
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired
    public NeedyService(JdbcTemplate jdbc, NeedyRepository repo, PasswordService passwords,
                        DeliveryPhotoStorage deliveryPhotos) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.passwords = passwords;
        this.deliveryPhotos = deliveryPhotos;
    }
    /** Constructor retained for focused JDBC tests without filesystem storage. */
    public NeedyService(JdbcTemplate jdbc, NeedyRepository repo, PasswordService passwords) {
        this(jdbc, repo, passwords, null);
    }
    @Transactional
    public int registerNeedy(String name, String contact, String username, String rawPassword) {
        String hashed = passwords.hash(rawPassword);
        Integer needyId;
        try {
            needyId = jdbc.queryForObject(
                "INSERT INTO needy (name, contact, status, created_at) VALUES (?, ?, 'active', ?) RETURNING id",
                Integer.class, name, contact, OffsetDateTime.now());
            jdbc.update(
                "INSERT INTO users (username, hashed_password, role, related_id) "
                + "VALUES (?, ?, 'needy', ?)",
                username, hashed, needyId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "Username already taken");
        }
        return needyId;
    }
    @Transactional
    public int createTicket(int needyId, String items, String address, Double lat, Double lon,
            String availableTime, Integer lotId, String apartment, String floorNum, String entrance,
            boolean selfPickup) {
        if (selfPickup) {
            address = null;
            lat = null;
            lon = null;
            apartment = null;
            floorNum = null;
            entrance = null;
        }
        lockWritableRecipient(needyId);
        List<OffsetDateTime> last = jdbc.query(
            "SELECT last_received_at FROM needy_profile WHERE needy_id = ?",
            (rs, n) -> rs.getObject("last_received_at", OffsetDateTime.class), needyId);
        if (!last.isEmpty() && last.get(0) != null
                && last.get(0).isAfter(OffsetDateTime.now().minus(7, ChronoUnit.DAYS))) {
            throw new TicketCreateException("weekly_limit");
        }
        boolean active = !jdbc.queryForList(
            "SELECT 1 FROM tickets WHERE needy_id = ? AND status IN ('open','assigned') LIMIT 1",
            needyId).isEmpty();
        if (active) {
            throw new TicketCreateException("active_ticket_exists");
        }
        if (selfPickup && lotId == null) {
            throw new TicketCreateException("lot_required");
        }
        OffsetDateTime expiresAt = null;
        if (lotId != null) {
            int reserved = jdbc.update(
                "UPDATE lots SET quantity = quantity - 1 "
                + "WHERE id = ? AND status = 'active' AND quantity >= 1 "
                + "AND (expiry_date IS NULL OR expiry_date::date > CURRENT_DATE + INTERVAL '1 day')",
                lotId);
            if (reserved == 0) {
                throw new TicketCreateException("lot_unavailable");
            }
            expiresAt = OffsetDateTime.now().plusHours(selfPickup ? 2 : 48);
        }
        try {
            return jdbc.queryForObject(
                "INSERT INTO tickets (needy_id, items, address, lat, lon, available_time, lot_id, "
                + "quantity, expires_at, apartment, floor_num, entrance, self_pickup, qr_secret, "
                + "status, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?) RETURNING id",
                Integer.class,
                needyId, items, address, lat, lon, availableTime, lotId, 1.0, expiresAt,
                apartment, floorNum, entrance, selfPickup, Qr.generateSecret(), OffsetDateTime.now());
        } catch (DuplicateKeyException e) {
            throw new TicketCreateException("active_ticket_exists");
        }
    }
    @Transactional
    public Integer cancelTicket(int needyId, int ticketId) {
        lockWritableRecipient(needyId);
        List<Map<String, Object>> snapshotRows = jdbc.queryForList(
            "SELECT lot_id, assigned_volunteer_id FROM tickets WHERE id = ? AND needy_id = ?", ticketId, needyId);
        if (snapshotRows.isEmpty()) {
            throw new ApiException(404, "Ticket not found");
        }
        Integer expectedVolId = asInt(snapshotRows.get(0).get("assigned_volunteer_id"));
        Integer expectedLotId = asInt(snapshotRows.get(0).get("lot_id"));
        // Canonical order: lot -> route -> ticket. Recheck this snapshot below.
        if (expectedLotId != null) {
            jdbc.queryForList("SELECT id FROM lots WHERE id = ? FOR UPDATE", expectedLotId);
        }
        Map<String, Object> lockedRoute = null;
        if (expectedVolId != null) {
            List<Map<String, Object>> routes = jdbc.queryForList(
                "SELECT id, points FROM volunteer_routes WHERE volunteer_id = ? "
                    + "AND status = 'in_progress' ORDER BY id FOR UPDATE", expectedVolId);
            if (!routes.isEmpty()) {
                lockedRoute = routes.get(0);
            }
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM tickets WHERE id = ? AND needy_id = ? FOR UPDATE", ticketId, needyId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "Ticket not found");
        }
        Map<String, Object> ticket = rows.get(0);
        Integer actualVolId = asInt(ticket.get("assigned_volunteer_id"));
        if (!java.util.Objects.equals(expectedVolId, actualVolId)
                || !java.util.Objects.equals(expectedLotId, asInt(ticket.get("lot_id")))) {
            throw new ApiException(409, "Заявка уже изменилась — обновите страницу и повторите отмену");
        }
        String status = (String) ticket.get("status");
        if (!"open".equals(status) && !"assigned".equals(status)) {
            throw new ApiException(400, "Можно отменить только открытую или назначенную заявку");
        }
        Integer volId = actualVolId;
        String proof = ticket.get("delivery_photo") instanceof String s ? s : null;
        int updated = jdbc.update(
            "UPDATE tickets SET status = 'cancelled', assigned_volunteer = NULL, "
            + "assigned_volunteer_id = NULL, delivery_photo = NULL, delivery_photo_status = NULL, "
            + "delivery_photo_ai_verdict = NULL, delivery_photo_ai_score = NULL, "
            + "delivery_photo_ai_notes = NULL, delivery_photo_reviewed_at = NULL "
            + "WHERE id = ? AND status IN ('open', 'assigned')",
            ticketId);
        if (updated == 0) {
            throw new ApiException(409, "Заявка уже изменила статус — обновите страницу");
        }
        if (proof != null && deliveryPhotos != null) {
            deliveryPhotos.deleteAfterCommit(proof);
        }
        Integer lotId = asInt(ticket.get("lot_id"));
        if (lotId != null) {
            double qty = ticket.get("quantity") instanceof Number n ? n.doubleValue() : 1.0;
            jdbc.update("UPDATE lots SET quantity = quantity + ? WHERE id = ? AND status = 'active'",
                qty, lotId);
        }
        if (volId != null) {
            dropStopFromLockedRoute(lockedRoute, ticketId);
            jdbc.update(
                "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) "
                + "VALUES (?, ?, ?, ?, 0)",
                volId, "ticket_cancelled",
                "Получатель отменил заявку #" + ticketId + " — точка снята с вашего маршрута.",
                OffsetDateTime.now());
        }
        return volId;
    }
    /** Mark the cancelled recipient's stop done on an already route-locked active route. */
    private void dropStopFromLockedRoute(Map<String, Object> route, int ticketId) {
        if (route == null) {
            return;
        }
        List<Map<String, Object>> points;
        try {
            String json = (String) route.get("points");
            points = json == null || json.isBlank() ? new ArrayList<>()
                : mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            points = new ArrayList<>();
        }
        boolean changed = false;
        for (Map<String, Object> p : points) {
            if ("ticket".equals(p.get("kind")) && asInt(p.get("ticket_id")) != null
                    && asInt(p.get("ticket_id")) == ticketId && !Boolean.TRUE.equals(p.get("done"))) {
                p.put("done", true);
                p.put("cancelled", true);
                RoutePointPrivacy.redactTicketPoint(p);
                changed = true;
            }
        }
        if (changed) {
            try {
                jdbc.update("UPDATE volunteer_routes SET points = ?, last_activity_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'in_progress'",
                    mapper.writeValueAsString(points), route.get("id"));
            } catch (Exception ignored) {
            }
        }
    }
    @Transactional
    public void rateDelivery(int needyId, int ticketId, int rating, String comment) {
        lockWritableRecipient(needyId);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM tickets WHERE id = ? AND needy_id = ?", ticketId, needyId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "Ticket not found");
        }
        Map<String, Object> ticket = rows.get(0);
        if (!"fulfilled".equals(ticket.get("status"))) {
            throw new ApiException(400, "Can only rate fulfilled deliveries");
        }
        Integer volId = asInt(ticket.get("assigned_volunteer_id"));
        if (volId == null) {
            throw new ApiException(400, "Оценка доступна только для доставок волонтёром");
        }
        jdbc.update(
            "INSERT INTO delivery_ratings (ticket_id, volunteer_id, rating, comment) "
            + "VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (ticket_id) DO UPDATE SET rating = EXCLUDED.rating, "
            + "comment = COALESCE(EXCLUDED.comment, delivery_ratings.comment)",
            ticketId, volId, rating, comment);
    }
    @Transactional
    public String setDeliveryPhotoPending(int needyId, int ticketId, String photoUrl) {
        lockWritableRecipient(needyId);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM tickets WHERE id = ? AND needy_id = ?", ticketId, needyId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "Ticket not found");
        }
        Map<String, Object> ticket = rows.get(0);
        if (!"fulfilled".equals(ticket.get("status"))) {
            throw new ApiException(400, "Can only upload photos for fulfilled deliveries");
        }
        jdbc.update(
            "UPDATE tickets SET delivery_photo = ?, delivery_photo_status = 'pending', "
            + "delivery_photo_ai_verdict = NULL, delivery_photo_ai_score = NULL, "
            + "delivery_photo_ai_notes = NULL, delivery_photo_reviewed_at = NULL WHERE id = ?",
            photoUrl, ticketId);
        String previous = (String) ticket.get("delivery_photo");
        if (previous != null && !previous.equals(photoUrl) && deliveryPhotos != null) {
            deliveryPhotos.deleteAfterCommit(previous);
        }
        return previous;
    }
    /** Update recipient account PII while serialized with account erasure. */
    @Transactional
    public Map<String, Object> updateNeedy(int needyId, String name, String contact) {
        lockWritableRecipient(needyId);
        return repo.updateNeedy(needyId, name, contact);
    }
    /** Upsert recipient profile/address PII while serialized with account erasure. */
    @Transactional
    public Map<String, Object> createOrUpdateProfile(int needyId, String address, Integer familySize,
            String preferences, String urgency, String availableTime, String apartment,
            String floorNum, String entrance, String city, Double lat, Double lon,
            boolean clearCoordinates) {
        lockWritableRecipient(needyId);
        return repo.createOrUpdateProfile(needyId, address, familySize, preferences, urgency,
            availableTime, apartment, floorNum, entrance, city, lat, lon, clearCoordinates);
    }
    /** Update the recipient's geo subscription while serialized with account erasure. */
    @Transactional
    public boolean setGeoPushEnabled(int needyId, boolean enabled) {
        lockWritableRecipient(needyId);
        return repo.setGeoPushEnabled(needyId, enabled);
    }
    /** Mark recipient-owned notification state while serialized with erasure. */
    @Transactional
    public void markNotificationRead(int needyId, int notificationId) {
        lockWritableRecipient(needyId);
        int updated = jdbc.update(
            "UPDATE notifications SET read = 1 WHERE id = ? AND needy_id = ?",
            notificationId, needyId);
        if (updated == 0) {
            throw new ApiException(404, "Notification not found");
        }
    }
    /** On-disk delivery-photo paths the controller must delete after a successful erase. */
    public record EraseResult(List<String> photos) {
    }
    @Transactional
    public EraseResult eraseAccount(int needyId) {
        List<Map<String, Object>> recipients = jdbc.queryForList(
            "SELECT status FROM needy WHERE id = ? FOR UPDATE", needyId);
        if (recipients.isEmpty()) {
            return null;
        }
        if ("deleted".equals(recipients.get(0).get("status"))) {
            return new EraseResult(List.of());
        }
        // The recipient lock prevents new owned tickets. Lock lots before discovering
        // routes: a startRoute waiting/committing ahead of us may create a new PII copy.
        List<Map<String, Object>> snapshot = jdbc.queryForList(
            "SELECT id, lot_id FROM tickets WHERE needy_id = ? ORDER BY id", needyId);
        List<Integer> ticketIds = snapshot.stream().map(t -> asInt(t.get("id"))).toList();
        List<Integer> lotIds = snapshot.stream().map(t -> asInt(t.get("lot_id")))
            .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        for (Integer lotId : lotIds) {
            jdbc.queryForList("SELECT id FROM lots WHERE id = ? FOR UPDATE", lotId);
        }
        List<Map<String, Object>> lockedRoutes = lockRouteCopies(ticketIds);
        List<Map<String, Object>> lockedTickets = jdbc.queryForList(
            "SELECT * FROM tickets WHERE needy_id = ? ORDER BY id FOR UPDATE", needyId);
        if (!snapshot.equals(lockedTickets.stream().map(t -> {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("id", t.get("id"));
            identity.put("lot_id", t.get("lot_id"));
            return identity;
        }).toList())) {
            throw new ApiException(409, "Заявка уже изменила статус — обновите страницу");
        }
        List<String> photos = lockedTickets.stream().map(t -> (String) t.get("delivery_photo"))
            .filter(java.util.Objects::nonNull).toList();
        if (deliveryPhotos != null) {
            photos.stream().distinct().forEach(deliveryPhotos::deleteAfterCommit);
        }
        List<Map<String, Object>> live = jdbc.queryForList(
            "UPDATE tickets SET status = 'cancelled' "
                + "WHERE needy_id = ? AND status IN ('open','assigned') "
                + "RETURNING id, lot_id, quantity, assigned_volunteer_id",
            needyId);
        for (Map<String, Object> t : live) {
            Integer lotId = asInt(t.get("lot_id"));
            if (lotId != null) {
                double qty = t.get("quantity") instanceof Number n ? n.doubleValue() : 1.0;
                jdbc.update("UPDATE lots SET quantity = quantity + ? WHERE id = ? AND status = 'active'",
                    qty, lotId);
            }
            Integer volId = asInt(t.get("assigned_volunteer_id"));
            if (volId != null) {
                jdbc.update(
                    "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) "
                    + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0)",
                    volId, "ticket_cancelled",
                    "Заявка #" + t.get("id")
                    + " отменена (аккаунт получателя удалён) — точка снята с маршрута.");
            }
        }
        jdbc.update(
            "UPDATE tickets SET items = NULL, address = NULL, lat = NULL, lon = NULL, "
            + "apartment = NULL, floor_num = NULL, entrance = NULL, available_time = NULL, "
            + "assigned_volunteer = NULL, delivery_photo = NULL, delivery_photo_status = NULL, "
            + "delivery_photo_ai_verdict = NULL, delivery_photo_ai_notes = NULL WHERE needy_id = ?",
            needyId);
        java.util.Set<Integer> cancelledTicketIds = live.stream()
            .map(row -> asInt(row.get("id")))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        scrubRouteCopies(lockedRoutes, ticketIds, cancelledTicketIds);
        jdbc.update(
            "DELETE FROM ticket_messages WHERE ticket_id IN (SELECT id FROM tickets WHERE needy_id = ?)",
            needyId);
        jdbc.update(
            "UPDATE delivery_ratings SET comment = NULL "
            + "WHERE ticket_id IN (SELECT id FROM tickets WHERE needy_id = ?)", needyId);
        jdbc.update("DELETE FROM notifications WHERE needy_id = ?", needyId);
        jdbc.update("DELETE FROM needy_profile WHERE needy_id = ?", needyId);
        jdbc.update("DELETE FROM users WHERE role = 'needy' AND related_id = ?", needyId);
        jdbc.update(
            "UPDATE needy SET name = 'Удалённый аккаунт', contact = NULL, status = 'deleted' "
            + "WHERE id = ?", needyId);
        return new EraseResult(photos);
    }
    private void lockWritableRecipient(int needyId) {
        List<String> statuses = jdbc.query(
            "SELECT status FROM needy WHERE id = ? FOR UPDATE",
            (rs, n) -> rs.getString("status"), needyId);
        if (statuses.isEmpty()) {
            throw new ApiException(404, "Needy not found");
        }
        if ("deleted".equals(statuses.get(0))) {
            throw new ApiException(403, "Account is not active");
        }
    }
    /** Lock active and historical copies before acquiring any ticket locks. */
    private List<Map<String, Object>> lockRouteCopies(List<Integer> ticketIds) {
        if (ticketIds.isEmpty()) {
            return List.of();
        }
        String alternatives = ticketIds.stream().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining("|"));
        String ticketPattern = "\\\"ticket_id\\\"\\s*:\\s*(" + alternatives + ")([^0-9]|$)";
        return jdbc.queryForList(
            "SELECT id, points FROM volunteer_routes WHERE points IS NOT NULL AND points ~ ? "
                + "ORDER BY id FOR UPDATE", ticketPattern);
    }
    /** Scrub only copies already locked before ticket mutation. */
    private void scrubRouteCopies(List<Map<String, Object>> routes, List<Integer> ticketIds,
                                 java.util.Set<Integer> cancelledTicketIds) {
        for (Map<String, Object> route : routes) {
            List<Map<String, Object>> points;
            try {
                points = mapper.readValue(route.get("points").toString(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            } catch (Exception ignored) {
                jdbc.update("UPDATE volunteer_routes SET points = '[]' WHERE id = ?", route.get("id"));
                continue;
            }
            boolean changed = false;
            for (Map<String, Object> point : points) {
                Integer ticketId = asInt(point.get("ticket_id"));
                if (ticketId != null && ticketIds.contains(ticketId)) {
                    if (cancelledTicketIds.contains(ticketId)) {
                        point.put("done", true);
                        point.put("cancelled", true);
                    }
                    changed |= RoutePointPrivacy.redactTicketPoint(point);
                }
            }
            if (changed) {
                try {
                    jdbc.update("UPDATE volunteer_routes SET points = ? WHERE id = ?",
                        mapper.writeValueAsString(points), route.get("id"));
                } catch (Exception e) {
                    throw new IllegalStateException("Could not scrub recipient route data", e);
                }
            }
        }
    }
    public void setProfileLastReceived(int needyId, OffsetDateTime ts) {
        int rows = jdbc.update(
            "UPDATE needy_profile SET last_received_at = ?, displaced_count = 0 WHERE needy_id = ?",
            ts, needyId);
        if (rows == 0) {
            jdbc.update("INSERT INTO needy_profile (needy_id, last_received_at) VALUES (?, ?)",
                needyId, ts);
        }
    }
    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
