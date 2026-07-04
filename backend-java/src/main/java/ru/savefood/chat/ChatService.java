package ru.savefood.chat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.savefood.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

    public ChatService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    public Map<String, Object> addMessage(int ticketId, String senderRole, int senderId, String body) {
        return jdbc.queryForList(
            "INSERT INTO ticket_messages (ticket_id, sender_role, sender_id, body) VALUES (?, ?, ?, ?) "
            + "RETURNING id, sender_role, sender_id, body, created_at",
            ticketId, senderRole, senderId, body).get(0);
    }

    private static Integer intOrNull(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }
}
