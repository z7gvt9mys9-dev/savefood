package ru.savefood.telegram;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class TelegramWebhookController {
    private static final Logger log = Logger.getLogger(TelegramWebhookController.class.getName());
    private static final ResponseEntity<Map<String, Object>> OK =
        ResponseEntity.ok(Map.of("ok", true));
    private final TelegramUpdateInbox inbox;
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String webhookSecret;
    public TelegramWebhookController(TelegramUpdateInbox inbox,
                                     @Value("${savefood.telegram.webhook-secret:}") String webhookSecret) {
        this.inbox = inbox;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.strip();
    }
    @PostMapping("/telegram/webhook")
    public ResponseEntity<Map<String, Object>> webhook(HttpServletRequest request) throws IOException {
        if (webhookSecret.isEmpty()) {
            log.warning("[telegram] webhook hit but TELEGRAM_WEBHOOK_SECRET is unset — update ignored");
            return OK;
        }
        String provided = request.getHeader("X-Telegram-Bot-Api-Secret-Token");
        if (!webhookSecret.equals(provided)) {
            log.warning("[telegram] webhook secret mismatch — update ignored");
            return OK;
        }
        // Authenticate before reading/parsing. Bound chunked requests as well as Content-Length.
        if (request.getContentLengthLong() > TelegramUpdateInbox.MAX_PAYLOAD_BYTES) {
            return ResponseEntity.status(413).body(Map.of("error", "Update too large"));
        }
        byte[] body = request.getInputStream().readNBytes(TelegramUpdateInbox.MAX_PAYLOAD_BYTES + 1);
        if (body.length > TelegramUpdateInbox.MAX_PAYLOAD_BYTES) {
            return ResponseEntity.status(413).body(Map.of("error", "Update too large"));
        }
        JsonNode update;
        try {
            update = mapper.readTree(body);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid update"));
        }
        JsonNode id = update == null ? null : update.get("update_id");
        if (update == null || !update.isObject() || id == null || !id.isIntegralNumber()
                || !id.canConvertToLong() || id.longValue() < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid update_id"));
        }
        // Retain only immutable input actually used by the bot, not arbitrary metadata/secrets.
        var payload = mapper.createObjectNode().put("update_id", id.longValue());
        JsonNode message = update.path("message");
        if (message.isObject()) {
            var stored = payload.putObject("message");
            stored.putObject("chat").put("id", message.path("chat").path("id").asText(""))
                .put("type", message.path("chat").path("type").asText(""));
            stored.putObject("from").put("id", message.path("from").path("id").asText(""));
            stored.put("text", message.path("text").asText(""));
        }
        if (payload.toString().getBytes(StandardCharsets.UTF_8).length > TelegramUpdateInbox.MAX_PAYLOAD_BYTES) {
            return ResponseEntity.status(413).body(Map.of("error", "Update too large"));
        }
        inbox.accept(id.longValue(), payload);
        return OK;
    }
}
