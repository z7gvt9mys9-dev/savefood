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
@Service
public class RouteRevertService {
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
        }
        return false;
    }
    public void revertRouteLot(Integer lotId, String pointsJson) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Integer> ticketIds = ticketIds(pointsJson);
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
            for (Integer tid : ticketIds) {
                discardCourierProof(tid);
                jdbc.update(
                    "UPDATE tickets SET status = 'open', assigned_volunteer = NULL, "
                    + "assigned_volunteer_id = NULL WHERE id = ? AND status = 'assigned'",
                    tid);
            }
            return;
        }
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
        }
        return ids;
    }
}
