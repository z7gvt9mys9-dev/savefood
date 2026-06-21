package kz.savefood.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import kz.savefood.chat.dto.MessageIn;
import kz.savefood.security.Auth;
import kz.savefood.security.CurrentUser;
import kz.savefood.web.ApiException;
import kz.savefood.web.RateLimiter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Java port of backend/chat_routes.py — REST for the in-app ticket chat (§53).
 * The thread is visible to the recipient, the assigned volunteer and admins;
 * posting is allowed only while the ticket is 'assigned'. Admins observe but
 * cannot post. The Telegram / Web-Push mirrors a posted message triggers stay on
 * the Python notifier — the in-app thread written here is the source of truth.
 */
@RestController
public class ChatController {

    private static final int MAX_BODY = 2000;

    private final ChatService chat;
    private final RateLimiter rateLimiter;

    public ChatController(ChatService chat, RateLimiter rateLimiter) {
        this.chat = chat;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/tickets/{ticketId}/messages")
    public List<Map<String, Object>> getMessages(@PathVariable int ticketId,
                                                 @RequestParam(name = "after_id", defaultValue = "0") int afterId,
                                                 @Auth CurrentUser user) {
        requireParticipant(ticketId, user);
        return chat.listMessages(ticketId, afterId);
    }

    @PostMapping("/tickets/{ticketId}/messages")
    public Map<String, Object> postMessage(@PathVariable int ticketId, @RequestBody MessageIn payload,
                                           @Auth CurrentUser user, HttpServletRequest request) {
        rateLimiter.check("chat:post", request.getRemoteAddr(), 30);
        if (payload.body() == null || payload.body().isEmpty() || payload.body().length() > MAX_BODY) {
            throw new ApiException(422, "body: длина должна быть от 1 до " + MAX_BODY + " символов");
        }
        Map<String, Object> ctx = ticketContext(ticketId);
        String role = chat.participantRole(user, ctx);
        if (role == null) {
            throw new ApiException(403, "Forbidden");
        }
        // Admins observe; the live conversation is between the two real parties.
        if ("admin".equals(role)) {
            throw new ApiException(403, "Администратор не участвует в чате");
        }
        if (!"assigned".equals(ctx.get("status"))) {
            throw new ApiException(400, "Чат доступен, пока заявка в работе у волонтёра");
        }
        Object assignedVolunteer = ctx.get("assigned_volunteer_id");
        if (assignedVolunteer == null) {
            throw new ApiException(400, "На заявку ещё не назначен волонтёр");
        }
        int senderId = "needy".equals(role)
            ? ((Number) ctx.get("needy_id")).intValue()
            : ((Number) assignedVolunteer).intValue();
        return chat.addMessage(ticketId, role, senderId, payload.body());
    }

    private Map<String, Object> requireParticipant(int ticketId, CurrentUser user) {
        Map<String, Object> ctx = ticketContext(ticketId);
        if (chat.participantRole(user, ctx) == null) {
            throw new ApiException(403, "Forbidden");
        }
        return ctx;
    }

    private Map<String, Object> ticketContext(int ticketId) {
        Map<String, Object> ctx = chat.getTicketContext(ticketId);
        if (ctx == null) {
            throw new ApiException(404, "Ticket not found");
        }
        return ctx;
    }
}
