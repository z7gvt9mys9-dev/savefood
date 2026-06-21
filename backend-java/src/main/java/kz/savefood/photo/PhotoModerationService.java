package kz.savefood.photo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Pre-publication moderation of recipient delivery photos (§36.1), ported from
 * photo_moderation.py. A photo lands as {@code delivery_photo_status = 'pending'}
 * (the public Impact feed only shows {@code 'approved'}); one Gemini Vision call
 * asks "is this actually food, and is it appropriate?" and leaves a verdict for
 * the admin queue. When PHOTO_AUTO_MODERATE is on, confident food is
 * auto-approved and clearly inappropriate content is auto-rejected (and its file
 * deleted). Fired fire-and-forget from a daemon thread after the upload.
 */
@Service
public class PhotoModerationService {

    private static final Logger log = Logger.getLogger(PhotoModerationService.class.getName());

    private static final double OK_THRESHOLD = 0.7;
    private static final double BAD_THRESHOLD = 0.3;

    private static final Map<String, String> MIME_BY_EXT = Map.of(
        ".jpg", "image/jpeg", ".jpeg", "image/jpeg", ".png", "image/png", ".webp", "image/webp");

    private static final String SYSTEM_PROMPT = """
        Ты — модератор публичной витрины платформы SaveFood (Казахстан).
        Получатель помощи загрузил фото полученных им продуктов — это фото попадёт в
        ПУБЛИЧНУЮ ленту, которую смотрят СМИ и городские администрации.

        Твоя задача — решить, можно ли это фото публиковать. На фото должна быть еда
        (продукты, готовые блюда, пакеты/коробки с продуктами). Недопустимо:
        люди крупным планом, документы с персональными данными, оскорбительный или
        непристойный контент, насилие, мемы/скриншоты, реклама, изображения не по теме.

        Верни СТРОГО один JSON-объект без пояснений:
        {
          "is_food": true|false,
          "depicts": "1-3 слова: что изображено",
          "inappropriate": true|false,
          "inappropriate_reason": "пояснение или null",
          "quality_ok": true|false,
          "summary": "1-2 предложения для модератора на русском"
        }
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();
    private final String apiKey;
    private final String model;
    private final boolean autoModerate;
    private final double autoApproveScore;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "photo-check");
        t.setDaemon(true);
        return t;
    });

    public PhotoModerationService(JdbcTemplate jdbc,
            @Value("${savefood.gemini-api-key:}") String apiKey,
            @Value("${savefood.photo-model:${savefood.ocr-model:gemini-2.5-flash}}") String model,
            @Value("${savefood.photo-auto-moderate:false}") boolean autoModerate,
            @Value("${savefood.photo-auto-approve-score:0.85}") double autoApproveScore) {
        this.jdbc = jdbc;
        this.apiKey = apiKey;
        this.model = model;
        this.autoModerate = autoModerate;
        this.autoApproveScore = autoApproveScore;
    }

    /** Fire-and-forget entry point, the analogue of {@code start_photo_check}. */
    public void startPhotoCheck(int ticketId, String photoPath) {
        pool.submit(() -> runPhotoCheck(ticketId, photoPath));
    }

    void runPhotoCheck(int ticketId, String photoPath) {
        try {
            Path path = Paths.get(photoPath);
            if (!Files.isRegularFile(path)) {
                return;
            }
            String photoUrl = "/volunteer_uploads/" + path.getFileName();
            String mime = MIME_BY_EXT.getOrDefault(extension(photoPath), "image/jpeg");
            byte[] content = Files.readAllBytes(path);
            JsonNode parsed = analyze(content, mime);
            if (parsed == null) {
                // AI unavailable → stays pending for fully manual review.
                saveResult(ticketId, photoUrl, "pending", "unchecked", null,
                    "ИИ-проверка недоступна, проверьте вручную", false);
                return;
            }
            Scored result = score(parsed);
            String auto = decide(result);
            String notes = result.notes;
            if ("approved".equals(auto)) {
                notes = truncate("[авто-одобрено ИИ] " + notes);
            } else if ("rejected".equals(auto)) {
                notes = truncate("[авто-отклонено ИИ] " + notes);
            }
            boolean applied = saveResult(ticketId, photoUrl, auto != null ? auto : "pending",
                result.verdict, result.score, notes, auto != null);
            if (applied && "rejected".equals(auto)) {
                // Drop the analysed file so it never leaks; the row keeps the verdict.
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best-effort, like the Python os.remove in a failure branch
                }
            }
            log.info("[photo] ticket " + ticketId + ": " + result.verdict + " (" + result.score + ") → "
                + (auto != null ? auto : "pending") + (applied ? "" : " (stale, not applied)"));
        } catch (Exception e) {
            log.warning("[photo] check for ticket " + ticketId + " failed: " + e.getMessage());
        }
    }

    private JsonNode analyze(byte[] content, String mime) {
        if (apiKey == null || apiKey.isBlank() || content == null || content.length == 0) {
            return null;
        }
        try {
            Map<String, Object> inlineData = new LinkedHashMap<>();
            inlineData.put("mime_type", mime == null || mime.isBlank() ? "image/jpeg" : mime);
            inlineData.put("data", Base64.getEncoder().encodeToString(content));
            Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(
                        Map.of("inline_data", inlineData),
                        Map.of("text", "Проверь это фото для публичной ленты.")))),
                "generationConfig", Map.of(
                    "responseMimeType", "application/json", "maxOutputTokens", 1000));

            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(60))
                .header("x-goog-api-key", apiKey)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warning("[photo] Gemini API " + resp.statusCode() + ": "
                    + resp.body().substring(0, Math.min(200, resp.body().length())));
                return null;
            }
            JsonNode candidates = mapper.readTree(resp.body()).path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidates.get(0).path("content").path("parts")) {
                text.append(part.path("text").asText(""));
            }
            JsonNode parsed = mapper.readTree(stripFence(text.toString().strip()));
            return parsed.isObject() ? parsed : null;
        } catch (Exception e) {
            log.warning("[photo] analysis failed: " + e.getMessage());
            return null;
        }
    }

    /** Deterministic scoring of the AI's structured answer (0=reject, 1=ok). */
    private Scored score(JsonNode parsed) {
        // Inappropriate content is a hard veto regardless of "is_food".
        if (parsed.path("inappropriate").asBoolean(false)) {
            String reason = parsed.path("inappropriate_reason").asText("");
            return new Scored(0.0, "inappropriate",
                truncate("Недопустимо: " + (reason.isBlank() ? "недопустимый контент" : reason)
                    + ". " + parsed.path("summary").asText("")), true);
        }
        List<String> notes = new ArrayList<>();
        double score = 0.5;
        if (parsed.path("is_food").asBoolean(false)) {
            score += 0.4;
        } else {
            score -= 0.4;
            String depicts = parsed.path("depicts").asText("");
            notes.add("на фото не еда (" + (depicts.isBlank() ? "?" : depicts) + ")");
        }
        JsonNode quality = parsed.get("quality_ok");
        if (quality != null && quality.isBoolean() && !quality.asBoolean()) {
            score -= 0.2;
            notes.add("низкое качество фото");
        }
        score = Math.max(0.0, Math.min(1.0, Math.round(score * 100.0) / 100.0));
        String verdict = score >= OK_THRESHOLD ? "food" : score <= BAD_THRESHOLD ? "not_food" : "review";
        String summary = parsed.path("summary").asText("").strip();
        String tail = String.join("; ", notes);
        String note = (summary + (tail.isBlank() ? "" : " — " + tail)).strip();
        return new Scored(score, verdict, truncate(note), false);
    }

    /** Pure auto-moderation decision → "approved" | "rejected" | null (queue). */
    private String decide(Scored result) {
        if (!autoModerate) {
            return null;
        }
        if (result.inappropriate) {
            return "rejected";
        }
        if ("food".equals(result.verdict) && result.score >= autoApproveScore) {
            return "approved";
        }
        return null;
    }

    /**
     * Only touch a row still 'pending' AND still pointing at the analysed photo: a
     * human decision must never be overwritten, and a re-upload (a different file)
     * must not inherit this stale verdict. Returns true if the row was updated.
     */
    private boolean saveResult(int ticketId, String photoUrl, String status, String verdict,
            Double score, String notes, boolean reviewed) {
        OffsetDateTime reviewedAt = reviewed ? OffsetDateTime.now() : null;
        int rows = jdbc.update(
            "UPDATE tickets SET delivery_photo_status = ?, delivery_photo_ai_verdict = ?, "
            + "delivery_photo_ai_score = ?, delivery_photo_ai_notes = ?, delivery_photo_reviewed_at = ? "
            + "WHERE id = ? AND delivery_photo_status = 'pending' AND delivery_photo = ?",
            status, verdict, score, notes, reviewedAt, ticketId, photoUrl);
        return rows > 0;
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot).toLowerCase();
    }

    private static String stripFence(String text) {
        if (text.startsWith("```")) {
            text = text.replaceAll("^`+", "").replaceAll("`+$", "");
            if (text.startsWith("json")) {
                text = text.substring(4);
            }
        }
        return text.strip();
    }

    private static String truncate(String s) {
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    private record Scored(double score, String verdict, String notes, boolean inappropriate) {
    }
}
