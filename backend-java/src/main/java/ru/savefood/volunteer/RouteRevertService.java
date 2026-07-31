package ru.savefood.volunteer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.savefood.photo.DeliveryPhotoStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Releases a route's lot and resolves its tickets when a route is torn down
 * (finish / admin reset / timeout / anti-fraud).
 *
 * <p>First question: <b>did the volunteer already pick the food up?</b> The shop
 * point carries that answer ({@code done}), and the two cases are genuinely
 * different:
 * <ul>
 *   <li><b>Picked up</b> — the food is in the volunteer's car. Putting the lot
 *       back to 'active' would advertise stock the shop does not have, and would
 *       close the shop's «Подтвердить передачу» window for good (that handler
 *       requires 'taken'). So the lot stays 'taken' and only the undelivered
 *       tickets are cancelled.</li>
 *   <li><b>Not picked up</b> — the food never left the shelf, so the lot goes
 *       back on the витрина. Then branch on whether the revert actually hit:
 *       revert hit → reopen the assigned tickets to 'open'; revert missed (lot
 *       meanwhile confirmed/expired/removed) → cancel them, so they don't strand
 *       'open' on a dead lot.</li>
 * </ul>
 * Must run inside the caller's transaction (the reset endpoint is @Transactional).
 */
@Service
public class RouteRevertService {

    // COALESCE(initial_quantity, quantity) degrades safely for legacy NULL lots;
    // GREATEST(...,0) clamps in case of any data drift.
    private static final String LOT_REVERT_SQL =
        "UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL, "
        + "quantity = GREATEST(COALESCE(initial_quantity, quantity) - COALESCE("
        + "(SELECT SUM(t.quantity) FROM tickets t WHERE t.lot_id = lots.id "
        + "AND t.status IN ('open', 'assigned', 'fulfilled')), 0), 0) "
        + "WHERE id = ? AND status = 'taken'";

    private final JdbcTemplate jdbc;
    private final DeliveryPhotoStorage deliveryPhotos;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public RouteRevertService(JdbcTemplate jdbc, DeliveryPhotoStorage deliveryPhotos) {
        this.jdbc = jdbc;
        this.deliveryPhotos = deliveryPhotos;
    }

    /** Kept for focused JDBC tests that do not need filesystem cleanup. */
    public RouteRevertService(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    /**
     * True once the volunteer confirmed pickup at the shop — the food has
     * physically left the shelf and the lot must not go back onto the витрина.
     */
    static boolean pickedUp(String pointsJson, ObjectMapper mapper) {
        if (pointsJson == null || pointsJson.isBlank()) {
            return false;
        }
        try {
            JsonNode points = mapper.readTree(pointsJson);
            if (points != null && points.isArray()) {
                for (JsonNode p : points) {
                    JsonNode kind = p.get("kind");
                    if (kind != null && "shop".equals(kind.asText())) {
                        JsonNode done = p.get("done");
                        return done != null && done.asBoolean(false);
                    }
                }
            }
        } catch (Exception e) {
            // malformed points → treat as "not picked up", the conservative branch
        }
        return false;
    }

    public void revertRouteLot(Integer lotId, String pointsJson) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Integer> ticketIds = ticketIds(pointsJson);

        // The food already left the shop: putting the lot back on the витрина
        // would advertise stock that is physically in the volunteer's car, and it
        // would also slam shut the shop's «Подтвердить передачу» window (that
        // handler requires status='taken'). Leave the lot 'taken' and only resolve
        // the undelivered tickets.
        if (pickedUp(pointsJson, mapper)) {
            for (Integer tid : ticketIds) {
                discardCourierProof(tid);
                cancelAssignedTicket(tid, now,
                    "Заявка #" + tid + " отменена: доставка не состоялась, лот уже забран из магазина. "
                    + "Выберите другой лот — недельный лимит не потрачен.");
            }
            return;
        }

        boolean lotGone = false;
        if (lotId != null) {
            int rows = jdbc.update(LOT_REVERT_SQL, lotId);
            lotGone = rows == 0;
        }

        if (!lotGone) {
            // Lot is back on the витрина — reopen its tickets for another volunteer.
            for (Integer tid : ticketIds) {
                discardCourierProof(tid);
                jdbc.update(
                    "UPDATE tickets SET status = 'open', assigned_volunteer = NULL, "
                    + "assigned_volunteer_id = NULL WHERE id = ? AND status = 'assigned'",
                    tid);
            }
            return;
        }

        // Lot is gone (confirmed/expired/removed): cancel the tickets and notify,
        // freeing the recipient's one-active-ticket slot (audit Q4 / §57).
        for (Integer tid : ticketIds) {
            discardCourierProof(tid);
            cancelAssignedTicket(tid, now,
                "Заявка #" + tid + " отменена: лот уже передан или снят магазином. "
                + "Выберите другой лот — недельный лимит не потрачен.");
        }
    }

    /** Cancel one still-assigned ticket and tell its recipient why. */
    private void cancelAssignedTicket(Integer ticketId, Timestamp now, String message) {
        List<Integer> needyIds = jdbc.query(
            "UPDATE tickets SET status = 'cancelled' WHERE id = ? AND status = 'assigned' "
            + "RETURNING needy_id",
            (rs, n) -> (Integer) rs.getObject("needy_id"), ticketId);
        if (!needyIds.isEmpty() && needyIds.get(0) != null) {
            jdbc.update(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) "
                + "VALUES (?, ?, ?, ?, 0)",
                needyIds.get(0), "ticket_cancelled", message, now);
        }
    }

    /**
     * A proof captured before QR confirmation is valid only for this still
     * assigned route stop. Route teardown must scrub it before reopening or
     * cancelling the ticket; otherwise a later volunteer could inherit it.
     */
    private void discardCourierProof(Integer ticketId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT delivery_photo FROM tickets WHERE id = ? AND status = 'assigned' FOR UPDATE", ticketId);
        if (rows.isEmpty()) {
            return;
        }
        String photo = rows.get(0).get("delivery_photo") instanceof String s ? s : null;
        if (photo == null || photo.isBlank()) {
            return;
        }
        int updated = jdbc.update(
            "UPDATE tickets SET delivery_photo = NULL, delivery_photo_status = NULL, "
                + "delivery_photo_ai_verdict = NULL, delivery_photo_ai_score = NULL, "
                + "delivery_photo_ai_notes = NULL, delivery_photo_reviewed_at = NULL "
                + "WHERE id = ? AND status = 'assigned' AND delivery_photo = ?", ticketId, photo);
        if (updated > 0 && deliveryPhotos != null) {
            deliveryPhotos.deleteAfterCommit(photo);
        }
    }

    private List<Integer> ticketIds(String pointsJson) {
        List<Integer> ids = new ArrayList<>();
        if (pointsJson == null || pointsJson.isBlank()) {
            return ids;
        }
        try {
            JsonNode points = mapper.readTree(pointsJson);
            if (points != null && points.isArray()) {
                for (JsonNode p : points) {
                    JsonNode kind = p.get("kind");
                    JsonNode tid = p.get("ticket_id");
                    if (kind != null && "ticket".equals(kind.asText())
                            && tid != null && !tid.isNull() && tid.asInt(0) != 0) {
                        ids.add(tid.asInt());
                    }
                }
            }
        } catch (Exception e) {
            // malformed points JSON → no tickets to resolve, like the Python except
        }
        return ids;
    }
}
