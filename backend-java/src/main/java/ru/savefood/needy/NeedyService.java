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
import ru.savefood.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional recipient mutations from backend/needy/{routes,db}.py — the work
 * the Python routes did inside a single {@code get_db_cursor()} block (register,
 * ticket creation with lot reservation, cancellation, rating, account erase). The
 * cross-module read helpers ({@code get_all_needy}, {@code set_profile_last_received})
 * are kept here too, as they are called from other modules' services.
 *
 * <p>Single-statement reads/writes live on {@link NeedyRepository}. The in-app
 * notification rows are written here; the best-effort Telegram ping to an
 * assigned volunteer is sent by the controller after the transaction commits
 * (the service returns the volunteer id for that purpose).
 */
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

    // ── Registration ───────────────────────────────────────────────────────────

    /**
     * Register a recipient + its login in one transaction (routes.py
     * {@code register_needy}): if the username is taken, the needy row rolls back too.
     */
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

    // ── Ticket creation (with lot reservation) ───────────────────────────────────

    /**
     * Create a ticket, atomically reserving a unit of the linked lot (db.py
     * {@code create_ticket}). Enforces the weekly limit, the one-active-ticket rule
     * (backed by {@code uq_tickets_one_active_per_needy}) and lot availability.
     * On any failure the whole transaction rolls back, so a reserved unit is
     * returned automatically.
     */
    @Transactional
    public int createTicket(int needyId, String items, String address, Double lat, Double lon,
            String availableTime, Integer lotId, String apartment, String floorNum, String entrance,
            boolean selfPickup) {
        // This service is also used by internal callers/tests. Keep the privacy
        // invariant here as well as at HTTP ingress: a self-pickup is redeemed
        // at the shop and must never persist a recipient's home details.
        if (selfPickup) {
            address = null;
            lat = null;
            lon = null;
            apartment = null;
            floorNum = null;
            entrance = null;
        }
        // §3.2: one assistance per 7 days (counted from the previous fulfilment).
        List<OffsetDateTime> last = jdbc.query(
            "SELECT last_received_at FROM needy_profile WHERE needy_id = ?",
            (rs, n) -> rs.getObject("last_received_at", OffsetDateTime.class), needyId);
        if (!last.isEmpty() && last.get(0) != null
                && last.get(0).isAfter(OffsetDateTime.now().minus(7, ChronoUnit.DAYS))) {
            throw new TicketCreateException("weekly_limit");
        }

        // Block parallel tickets: only one open/assigned ticket at a time per needy.
        boolean active = !jdbc.queryForList(
            "SELECT 1 FROM tickets WHERE needy_id = ? AND status IN ('open','assigned') LIMIT 1",
            needyId).isEmpty();
        if (active) {
            throw new TicketCreateException("active_ticket_exists");
        }

        // Self-pickup is confirmed by the shop against the ticket's lot — without
        // a lot the QR can never be matched to a shop.
        if (selfPickup && lotId == null) {
            throw new TicketCreateException("lot_required");
        }

        OffsetDateTime expiresAt = null;
        if (lotId != null) {
            // Atomic reservation: status, expiry AND quantity in one guarded update.
            int reserved = jdbc.update(
                "UPDATE lots SET quantity = quantity - 1 "
                + "WHERE id = ? AND status = 'active' AND quantity >= 1 "
                // Keep the reservation rule aligned with the volunteer map and
                // start_route. Reserving food that is already too close to expiry
                // created tickets no route was allowed to take.
                + "AND (expiry_date IS NULL OR expiry_date::date > CURRENT_DATE + INTERVAL '1 day')",
                lotId);
            if (reserved == 0) {
                throw new TicketCreateException("lot_unavailable");
            }
            // Self-pickup TTL 2h (recipient comes now); delivery TTL 48h, after
            // which reservation_ttl_tick frees the unit and the active-ticket slot.
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
            // uq_tickets_one_active_per_needy: a parallel request won the race. The
            // rollback returns the reserved unit automatically.
            throw new TicketCreateException("active_ticket_exists");
        }
    }

    // ── Ticket cancellation ──────────────────────────────────────────────────────

    /**
     * Cancel an open/assigned ticket on behalf of the recipient (routes.py
     * {@code cancel_ticket}): flip status, return the reserved unit to a still-active
     * lot, drop the stop from the assigned volunteer's in-progress route, and notify
     * the volunteer in-app. Returns the assigned volunteer's id (or null) so the
     * controller can send the Telegram ping after the transaction commits.
     */
    @Transactional
    public Integer cancelTicket(int needyId, int ticketId) {
        // Interactive route mutations lock route → ticket. Take a non-locking
        // snapshot only to find that route, lock it first, then lock/recheck the
        // ticket. This prevents a cancel-vs-complete deadlock and a stale points
        // JSON write overwriting a just-completed pickup/delivery.
        List<Map<String, Object>> snapshotRows = jdbc.queryForList(
            "SELECT assigned_volunteer_id FROM tickets WHERE id = ? AND needy_id = ?", ticketId, needyId);
        if (snapshotRows.isEmpty()) {
            throw new ApiException(404, "Ticket not found");
        }
        Integer expectedVolId = asInt(snapshotRows.get(0).get("assigned_volunteer_id"));
        Map<String, Object> lockedRoute = null;
        if (expectedVolId != null) {
            List<Map<String, Object>> routes = jdbc.queryForList(
                "SELECT id, points FROM volunteer_routes WHERE volunteer_id = ? "
                    + "AND status = 'in_progress' FOR UPDATE", expectedVolId);
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
        if (!java.util.Objects.equals(expectedVolId, actualVolId)) {
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

        // Guarded return: give the reserved quantity back only if the lot is still active.
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
                changed = true;
            }
        }
        if (changed) {
            try {
                jdbc.update("UPDATE volunteer_routes SET points = ?, last_activity_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'in_progress'",
                    mapper.writeValueAsString(points), route.get("id"));
            } catch (Exception ignored) {
                // serialization can't realistically fail for a list of maps
            }
        }
    }

    // ── Rating ───────────────────────────────────────────────────────────────────

    /**
     * Rate a fulfilled volunteer delivery (routes.py {@code rate_delivery}). The
     * thank-you note is optional: when omitted, any note already on file is kept.
     */
    @Transactional
    public void rateDelivery(int needyId, int ticketId, int rating, String comment) {
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

    // ── Impact photo ─────────────────────────────────────────────────────────────

    /**
     * Attach a recipient's impact photo as {@code pending} moderation and return the
     * replaced photo URL (now an orphan the caller deletes from disk). The ticket
     * must exist, belong to the recipient and be fulfilled (routes.py
     * {@code upload_impact_photo}).
     */
    @Transactional
    public String setDeliveryPhotoPending(int needyId, int ticketId, String photoUrl) {
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
        return (String) ticket.get("delivery_photo");
    }

    // ── Account erase (§49 «право на забвение») ──────────────────────────────────

    /** On-disk delivery-photo paths the controller must delete after a successful erase. */
    public record EraseResult(List<String> photos) {
    }

    /**
     * Erase a recipient's personal data (db.py {@code erase_account}): scrub PII from
     * tickets (keeping status/timestamps for aggregates), return live reservations,
     * close live tickets and notify assigned volunteers, delete messages/profile/
     * notifications and the login, and anonymise the needy row. Returns delivery
     * photo paths to remove from disk, or null if the needy row is missing.
     */
    @Transactional
    public EraseResult eraseAccount(int needyId) {
        if (repo.getNeedyById(needyId) == null) {
            return null;
        }

        List<String> photos = jdbc.query(
            "SELECT delivery_photo FROM tickets WHERE needy_id = ? AND delivery_photo IS NOT NULL",
            (rs, n) -> rs.getString("delivery_photo"), needyId);

        // Return reservations for live tickets to still-active lots.
        List<Map<String, Object>> liveLots = jdbc.queryForList(
            "SELECT id, lot_id, quantity FROM tickets WHERE needy_id = ? AND status IN ('open','assigned')",
            needyId);
        for (Map<String, Object> t : liveLots) {
            Integer lotId = asInt(t.get("lot_id"));
            if (lotId != null) {
                double qty = t.get("quantity") instanceof Number n ? n.doubleValue() : 1.0;
                jdbc.update("UPDATE lots SET quantity = quantity + ? WHERE id = ? AND status = 'active'",
                    qty, lotId);
            }
        }

        // Close live tickets and notify any assigned volunteer the stop is gone.
        List<Map<String, Object>> live = jdbc.queryForList(
            "SELECT id, assigned_volunteer_id FROM tickets WHERE needy_id = ? "
            + "AND status IN ('open','assigned')", needyId);
        if (!live.isEmpty()) {
            jdbc.update(
                "UPDATE tickets SET status = 'cancelled', assigned_volunteer = NULL, "
                + "assigned_volunteer_id = NULL WHERE needy_id = ? AND status IN ('open','assigned')",
                needyId);
            for (Map<String, Object> row : live) {
                Integer volId = asInt(row.get("assigned_volunteer_id"));
                if (volId != null) {
                    jdbc.update(
                        "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0)",
                        volId, "ticket_cancelled",
                        "Заявка #" + row.get("id")
                        + " отменена (аккаунт получателя удалён) — точка снята с маршрута.");
                }
            }
        }

        // Scrub PII from tickets but keep status/timestamps for platform aggregates.
        jdbc.update(
            "UPDATE tickets SET items = NULL, address = NULL, lat = NULL, lon = NULL, "
            + "apartment = NULL, floor_num = NULL, entrance = NULL, available_time = NULL, "
            + "assigned_volunteer = NULL, delivery_photo = NULL, delivery_photo_status = NULL, "
            + "delivery_photo_ai_verdict = NULL, delivery_photo_ai_notes = NULL WHERE needy_id = ?",
            needyId);
        // Chat threads and thank-you notes are the recipient's words — erase them too;
        // the numeric rating survives so volunteer averages don't shift retroactively.
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

    // ── Cross-module helpers (kept from the original NeedyService) ─────────────────

    /**
     * Record that a recipient was helped (db.py {@code set_profile_last_received}):
     * stamps {@code last_received_at} and resets the displacement counter (§59/Q1-C),
     * inserting a profile row if none exists yet.
     */
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
