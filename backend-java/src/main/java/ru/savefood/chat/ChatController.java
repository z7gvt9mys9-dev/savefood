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
        ChatService.AddedMessage added = chat.addMessage(ticketId, user, payload.body());
        String safe = Html.escape(payload.body());
        try {
            if ("needy".equals(added.senderRole())) {
                int volId = added.assignedVolunteerId();
                telegram.notifyVolunteer(volId, "◇ Получатель: " + safe);
                push.notifyRole("volunteer", volId, "Сообщение от получателя: " + payload.body(), "/volunteer");
            } else {
                int needyId = added.needyId();
                telegram.notifyNeedy(needyId, "◇ Волонтёр: " + safe);
                push.notifyRole("needy", needyId, "Сообщение от волонтёра: " + payload.body(), "/needy");
            }
        } catch (RuntimeException ignore) {
        }
        return added.message();
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
