package ru.savefood.telegram;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class TelegramWebhookController {
    private static final Logger log = Logger.getLogger(TelegramWebhookController.class.getName());
    private static final ResponseEntity<Map<String, Object>> OK =
        ResponseEntity.ok(Map.of("ok", true));
    private final TelegramBotService bot;
    private final String webhookSecret;
    public TelegramWebhookController(TelegramBotService bot,
                                     @Value("${savefood.telegram.webhook-secret:}") String webhookSecret) {
        this.bot = bot;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.strip();
    }
    @PostMapping("/telegram/webhook")
    public ResponseEntity<Map<String, Object>> webhook(@RequestBody(required = false) JsonNode update,
                                                       HttpServletRequest request) {
        if (webhookSecret.isEmpty()) {
            log.warning("[telegram] webhook hit but TELEGRAM_WEBHOOK_SECRET is unset — update ignored");
            return OK;
        }
        String provided = request.getHeader("X-Telegram-Bot-Api-Secret-Token");
        if (!webhookSecret.equals(provided)) {
            log.warning("[telegram] webhook secret mismatch — update ignored");
            return OK;
        }
        if (update != null && !update.isNull()) {
            bot.handleUpdate(update);
        }
        return OK;
    }
}
