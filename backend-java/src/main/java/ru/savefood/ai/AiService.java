package ru.savefood.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Port of backend/ai_service.py — the Gemini-backed support assistant for the
 * Telegram bot. {@link #askSupportAi} returns the model's answer, the
 * {@link #ESCALATE} sentinel when it cannot answer reliably, or {@code null} on
 * any failure (no API key, network error, empty response) so the caller escalates
 * to a human (SUPPORT_CHAT_ID).
 */
@Service
public class AiService {

    private static final Logger log = Logger.getLogger(AiService.class.getName());

    /** The sentinel the model returns when it cannot answer reliably. */
    public static final String ESCALATE = "ESCALATE";

    private static final String SYSTEM_PROMPT = """
        Ты — помощник службы поддержки платформы SaveFood (savefood — спасение еды).

        О платформе:
        - SaveFood соединяет магазины (отдают еду с истекающим сроком годности), волонтёров (доставляют) и нуждающихся (получают помощь бесплатно).
        - Магазин публикует «лот» (название, категория, количество кг, срок годности, адрес, время выдачи). Лот автоматически снимается за 24 часа до истечения срока.
        - Нуждающийся после обычной регистрации сразу может пользоваться функциями получателя; документы о статусе и модерация не требуются. Получать помощь можно не чаще раза в неделю. Одновременно может быть только одна активная заявка.
        - Нуждающийся выбирает лот на карте и оформляет заявку: доставка волонтёром или самовывоз. При самовывозе магазин сканирует QR-код получателя (SF-<номер>).
        - Волонтёр берёт лот на карте, приложение строит маршрут: магазин → получатели. В магазине жмёт «Я забрал», получатели получают уведомление с временем прибытия. Доставка подтверждается сканом QR-кода получателя + GPS-проверкой (радиус 100 м).
        - Если получатель не открыл дверь — волонтёр жмёт «Не открыли дверь» (после 3 попыток заявка возвращается в очередь).
        - Команды бота: /start — привязка аккаунта, /help — список команд, /status — статус аккаунта, /chat — как переписываться, /unlink — отвязать Telegram.
        - Текстовые сообщения боту пересылаются второй стороне активной доставки (волонтёр ↔ получатель).

        Правила ответа:
        - Отвечай кратко (1-4 предложения), дружелюбно, на языке пользователя (по умолчанию русский).
        - Отвечай ТОЛЬКО на вопросы о платформе SaveFood и её использовании.
        - Если не знаешь ответа, не уверен, либо вопрос требует действий человека (жалоба, спор, разблокировка, изменение чужих данных, возврат, инцидент, поведение другого пользователя) — ответь ровно одним словом: ESCALATE
        """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).build();

    private final String apiKey;
    private final String model;

    public AiService(@Value("${savefood.gemini-api-key:}") String apiKey,
                     @Value("${savefood.ai-model:${savefood.ocr-model:gemini-2.5-flash}}") String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    /** @return the answer, the {@link #ESCALATE} sentinel, or null on failure. */
    public String askSupportAi(String question, String role, String username) {
        if (apiKey == null || apiKey.isBlank() || question == null || question.isBlank()) {
            return null;
        }
        String userLine = "Пользователь (роль: " + (role == null ? "guest" : role)
            + (username != null ? ", логин: " + username : "")
            + ") спрашивает:\n" + question.strip();
        try {
            Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userLine)))),
                "generationConfig", Map.of("maxOutputTokens", 500));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(20))
                // Key in a header, not the query string, so it never lands in logs.
                .header("x-goog-api-key", apiKey)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                String b = resp.body();
                log.warning("[ai] Gemini API " + resp.statusCode() + ": "
                    + b.substring(0, Math.min(200, b.length())));
                return null;
            }
            JsonNode candidates = mapper.readTree(resp.body()).path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode p : candidates.get(0).path("content").path("parts")) {
                sb.append(p.path("text").asText(""));
            }
            String text = sb.toString().strip();
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            log.warning("[ai] request failed: " + e.getMessage());
            return null;
        }
    }
}
