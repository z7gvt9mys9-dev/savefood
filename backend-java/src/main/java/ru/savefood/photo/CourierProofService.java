package ru.savefood.photo;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
@Service
public class CourierProofService {
    private final JdbcTemplate jdbc;
    private final DeliveryPhotoStorage storage;
    public CourierProofService(JdbcTemplate jdbc, DeliveryPhotoStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discardForAssignedTicket(int ticketId) {
        List<String> refs = jdbc.query(
            "SELECT delivery_photo FROM tickets WHERE id = ? AND status = 'assigned' "
                + "AND delivery_photo IS NOT NULL FOR UPDATE",
            (rs, n) -> rs.getString("delivery_photo"), ticketId);
        if (refs.isEmpty()) {
            return;
        }
        int updated = jdbc.update(
            "UPDATE tickets SET delivery_photo = NULL, delivery_photo_status = NULL, "
                + "delivery_photo_ai_verdict = NULL, delivery_photo_ai_score = NULL, "
                + "delivery_photo_ai_notes = NULL, delivery_photo_reviewed_at = NULL "
                + "WHERE id = ? AND status = 'assigned'", ticketId);
        if (updated > 0) {
            refs.forEach(storage::deleteAfterCommit);
        }
    }
}
