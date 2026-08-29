package ru.savefood.chat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.savefood.security.CurrentUser;
import ru.savefood.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Port of backend/chat.py — the in-app ticket chat (§53): volunteer↔recipient
 * messages scoped to one ticket. The thread for ticket T is visible to exactly
 * three parties: the recipient who owns T, the volunteer assigned to T, and any
 * admin. The in-app thread is the source of truth; the best-effort Telegram /
 * Web-Push mirrors stay on the Python notifier during the migration.
 */
@Service
public class ChatService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ChatService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /** The ticket's chat participants + status, or null if no ticket. */
    public Map<String, Object> getTicketContext(int ticketId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, needy_id, assigned_volunteer_id, status FROM tickets WHERE id = ?", ticketId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Map the caller to their role in this thread, or null if not a participant. */
    public String participantRole(CurrentUser user, Map<String, Object> ctx) {
        if (user.isAdmin()) {
            return "admin";
        }
        Integer related = user.relatedId();
        if ("needy".equals(user.role()) && Objects.equals(related, intOrNull(ctx.get("needy_id")))) {
            return "needy";
        }
        Integer assignedVolunteer = intOrNull(ctx.get("assigned_volunteer_id"));
        if ("volunteer".equals(user.role()) && related != null && related.equals(assignedVolunteer)) {
            return "volunteer";
        }
        return null;
    }

    public List<Map<String, Object>> listMessages(int ticketId, int afterId) {
        return jdbc.queryForList(
            "SELECT id, sender_role, sender_id, body, created_at FROM ticket_messages "
            + "WHERE ticket_id = ? AND id > ? ORDER BY id ASC", ticketId, afterId);
    }

    /**
     * Lock the ticket and revalidate the live chat contract in the same transaction
     * that inserts the message. Ticket closure/reassignment updates therefore win
     * either before this check (and reject the message) or after this insert commits.
     */
    public AddedMessage addMessage(int ticketId, CurrentUser user, String body) {
        return tx.execute(ignored -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, needy_id, assigned_volunteer_id, status FROM tickets "
                + "WHERE id = ? FOR UPDATE", ticketId);
            if (rows.isEmpty()) {
                throw new ApiException(404, "Ticket not found");
            }
            Map<String, Object> ctx = rows.get(0);
            String role = participantRole(user, ctx);
            if (role == null) {
                throw new ApiException(403, "Forbidden");
            }
            if ("admin".equals(role)) {
                throw new ApiException(403, "Администратор не участвует в чате");
            }
            if (!"assigned".equals(ctx.get("status"))) {
                throw new ApiException(400, "Чат доступен, пока заявка в работе у волонтёра");
            }
            Integer assignedVolunteerId = intOrNull(ctx.get("assigned_volunteer_id"));
            if (assignedVolunteerId == null) {
                throw new ApiException(400, "На заявку ещё не назначен волонтёр");
            }
            int needyId = ((Number) ctx.get("needy_id")).intValue();
            int senderId = "needy".equals(role) ? needyId : assignedVolunteerId;
            Map<String, Object> message = jdbc.queryForList(
                "INSERT INTO ticket_messages (ticket_id, sender_role, sender_id, body) "
                + "VALUES (?, ?, ?, ?) RETURNING id, sender_role, sender_id, body, created_at",
                ticketId, role, senderId, body).get(0);
            return new AddedMessage(message, role, needyId, assignedVolunteerId);
        });
    }

    public record AddedMessage(Map<String, Object> message, String senderRole,
                               int needyId, int assignedVolunteerId) {
        public int counterpartId() {
            return "needy".equals(senderRole) ? assignedVolunteerId : needyId;
        }
    }

    private static Integer intOrNull(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }
}
