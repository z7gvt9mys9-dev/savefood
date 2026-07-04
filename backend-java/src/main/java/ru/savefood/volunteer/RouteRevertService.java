package ru.savefood.volunteer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Port of backend/background.py revert_route_lot. Releases a route's lot and
 * resolves its tickets when a route is torn down (admin reset / timeout / anti-
 * fraud). Reverts the lot FIRST, then branches on whether it came back to
 * 'active':
 * <ul>
 *   <li>revert hit → reopen the route's assigned tickets to 'open';</li>
 *   <li>revert missed (lot already confirmed/expired/removed) → cancel the
 *       tickets and notify, so they don't strand 'open' on a dead lot.</li>
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
    private final ObjectMapper mapper = new ObjectMapper();

    public RouteRevertService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void revertRouteLot(Integer lotId, String pointsJson) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Integer> ticketIds = ticketIds(pointsJson);

        boolean lotGone = false;
        if (lotId != null) {
            int rows = jdbc.update(LOT_REVERT_SQL, lotId);
            lotGone = rows == 0;
        }

        if (!lotGone) {
            // Lot is back on the витрина — reopen its tickets for another volunteer.
            for (Integer tid : ticketIds) {
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
            List<Integer> needyIds = jdbc.query(
                "UPDATE tickets SET status = 'cancelled' WHERE id = ? AND status = 'assigned' "
                + "RETURNING needy_id",
                (rs, n) -> (Integer) rs.getObject("needy_id"), tid);
            if (!needyIds.isEmpty() && needyIds.get(0) != null) {
                jdbc.update(
                    "INSERT INTO notifications (needy_id, type, payload, created_at, read) "
                    + "VALUES (?, ?, ?, ?, 0)",
                    needyIds.get(0), "ticket_cancelled",
                    "Заявка #" + tid + " отменена: лот уже передан или снят магазином. "
                    + "Выберите другой лот — недельный лимит не потрачен.",
                    now);
            }
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
