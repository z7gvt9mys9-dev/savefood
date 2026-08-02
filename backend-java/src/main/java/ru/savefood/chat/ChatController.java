package ru.savefood.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import ru.savefood.chat.dto.MessageIn;
import ru.savefood.push.PushDispatchService;
import ru.savefood.security.Auth;
import ru.savefood.security.CurrentUser;
import ru.savefood.telegram.TelegramService;
import ru.savefood.util.Html;
import ru.savefood.web.ApiException;
import ru.savefood.web.ClientIp;
import ru.savefood.web.RateLimiter;
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
 * cannot post. A posted message is mirrored best-effort to the counterpart's
 * Telegram and Web Push so they see it without the page open; the in-app thread
 * remains the source of truth.
 */
@RestController
public class ChatController {

    private static final int MAX_BODY = 2000;

    private final ChatService chat;
    private final RateLimiter rateLimiter;
    private final TelegramService telegram;
    private final PushDispatchService push;

    public ChatController(ChatService chat, RateLimiter rateLimiter,
                          TelegramService telegram, PushDispatchService push) {
        this.chat = chat;
        this.rateLimiter = rateLimiter;
        this.telegram = telegram;
        this.push = push;
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
        rateLimiter.check("chat:post", ClientIp.of(request), 30);
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
        Map<String, Object> msg = chat.addMessage(ticketId, role, senderId, payload.body());

        // Best-effort mirrors so the counterpart sees it without the page open.
        String safe = Html.escape(payload.body());
        try {
            if ("needy".equals(role)) {
                int volId = ((Number) assignedVolunteer).intValue();
                telegram.notifyVolunteer(volId, "◇ Получатель: " + safe);
                push.notifyRole("volunteer", volId, "Сообщение от получателя: " + payload.body(), "/volunteer");
            } else {
                int needyId = ((Number) ctx.get("needy_id")).intValue();
                telegram.notifyNeedy(needyId, "◇ Волонтёр: " + safe);
                push.notifyRole("needy", needyId, "Сообщение от волонтёра: " + payload.body(), "/needy");
            }
        } catch (RuntimeException ignore) {
            // best-effort, like the Python try/except: pass
        }
        return msg;
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
