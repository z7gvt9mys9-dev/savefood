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

/**
 * Telegram update intake (§16). Registered with
 * {@code https://api.telegram.org/bot<TOKEN>/setWebhook?url=<SITE>/telegram/webhook&secret_token=<SECRET>}.
 *
 * <p>Authentication is the shared secret Telegram echoes in
 * {@code X-Telegram-Bot-Api-Secret-Token} — this endpoint is deliberately outside
 * the {@code @Auth} surface, because the caller is Telegram, not a platform user.
 * With no secret configured the endpoint refuses to process anything: an open
 * update intake would let anyone forge a message from any chat id, which is
 * enough to hijack an account link.
 *
 * <p>Always answers 200, even on a malformed or rejected update. A non-2xx makes
 * Telegram redeliver the same update with growing backoff, so a single poisonous
 * message would stall the whole queue.
 */
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
