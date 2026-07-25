package ru.savefood.kyc;

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
import ru.savefood.audit.AuditService;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.VolunteerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Hybrid Auto-KYC (§58) of a recipient's eligibility document, ported from the
 * needy path of kyc_service.py. One Gemini Vision call reads the document;
 * deterministic scoring then decides: a confident {@code likely_ok} is
 * auto-approved (fast, harmless), while everything else — {@code likely_fraud},
 * {@code review} and {@code unchecked} (AI unavailable) — stays {@code pending}
 * for a human moderator. The AI never rejects a vulnerable applicant on its own;
 * refusals are a human decision (ethical requirement). Fired fire-and-forget from
 * a daemon thread after the upload.
 *
 * <p>The document is decrypted in memory only ({@link KycCrypto}); on disk it
 * stays encrypted at rest while pending and is deleted when a moderator decides
 * (§5). The best-effort Telegram ping + in-app notification row + audit entry are
 * written here.
 */
@Service
public class KycService {

    private static final Logger log = Logger.getLogger(KycService.class.getName());

    // Decision bands. Above OK ⇒ auto-approve, below FRAUD ⇒ auto-reject, the
    // middle is handed to a human moderator (PATCH /admin/{needy,volunteers}/{id}/moderation).
    // Widening the middle band trades moderator workload for fewer wrong automatic
    // decisions; narrowing it does the opposite. Configurable so that trade-off can
    // be tuned per deployment without a rebuild.
    private final double okThreshold;
    private final double fraudThreshold;
    private static final String VERDICT_OK = "likely_ok";
    private static final String VERDICT_REVIEW = "review";
    private static final String VERDICT_FRAUD = "likely_fraud";
    private static final String VERDICT_UNCHECKED = "unchecked";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_PENDING = "pending";

    private static final Map<String, String> MIME_BY_EXT = Map.of(
        ".jpg", "image/jpeg", ".jpeg", "image/jpeg", ".png", "image/png",
        ".webp", "image/webp", ".pdf", "application/pdf");

    private static final String SYSTEM_PROMPT = """
        Ты — система предварительной проверки документов платформы SaveFood (Казахстан).
        Нуждающийся загрузил документ, подтверждающий право на продуктовую помощь
        (справка о соц. статусе, удостоверение многодетной семьи, справка об инвалидности,
        справка о доходах, пенсионное удостоверение и т.п.).

        Верни СТРОГО один JSON-объект:
        {
          "is_document": true|false,
          "document_type": "краткое название типа документа или null",
          "supports_need": true|false,
          "holder_name": "ФИО владельца из документа или null",
          "name_matches": true|false|null,
          "legible": true|false,
          "tampering_signs": true|false,
          "tampering_reason": "пояснение или null",
          "summary": "1-2 предложения для модератора на русском"
        }

        Имя заявителя в анкете будет передано отдельной строкой. Сравнивай имена мягко:
        учитывай инициалы, порядок слов, транслитерацию (ru/kk/en).
        """;

    private static final String VOLUNTEER_SYSTEM_PROMPT = """
        Ты — система верификации личности волонтёров платформы SaveFood (Казахстан).
        Волонтёр загрузил документ, удостоверяющий личность (удостоверение личности РК,
        паспорт, водительское удостоверение и т.п.). Цель — убедиться, что это настоящий
        документ реального человека, а не чужое/поддельное/случайное фото.

        Верни СТРОГО один JSON-объект:
        {
          "is_document": true|false,
          "is_id_document": true|false,
          "document_type": "краткое название типа документа или null",
          "holder_name": "ФИО владельца из документа или null",
          "name_matches": true|false|null,
          "legible": true|false,
          "tampering_signs": true|false,
          "tampering_reason": "пояснение или null",
          "summary": "1-2 предложения для модератора на русском"
        }

        Имя волонтёра в анкете будет передано отдельной строкой. Сравнивай имена мягко:
        учитывай инициалы, порядок слов, транслитерацию (ru/kk/en).
        """;

    private final JdbcTemplate jdbc;
    private final NeedyRepository repo;
    private final VolunteerRepository volunteerRepo;
    private final AuditService audit;
    private final KycCrypto crypto;
    private final TelegramService telegram;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();
    private final String apiKey;
    private final String model;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "kyc-check");
        t.setDaemon(true);
        return t;
    });

    public KycService(JdbcTemplate jdbc, NeedyRepository repo, VolunteerRepository volunteerRepo,
                      AuditService audit, KycCrypto crypto, TelegramService telegram,
                      @Value("${savefood.gemini-api-key:}") String apiKey,
                      @Value("${savefood.kyc-model:${savefood.ocr-model:gemini-2.5-flash}}") String model,
                      @Value("${savefood.kyc.ok-threshold:0.7}") double okThreshold,
                      @Value("${savefood.kyc.fraud-threshold:0.3}") double fraudThreshold) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.volunteerRepo = volunteerRepo;
        this.audit = audit;
        this.crypto = crypto;
        this.telegram = telegram;
        this.apiKey = apiKey;
        this.model = model;
        this.okThreshold = okThreshold;
        this.fraudThreshold = fraudThreshold;
    }

    /** Fire-and-forget entry point, the analogue of {@code start_kyc_check}. */
    public void startKycCheck(int needyId, String documentPath, String applicantName) {
        pool.submit(() -> runKycCheck(needyId, documentPath, applicantName));
    }

    /** Synchronous re-check for the moderator's «второе мнение» (§38.2), needy path. */
    public void recheckNeedy(int needyId, String documentPath, String applicantName) {
        runKycCheck(needyId, documentPath, applicantName);
    }

    /** Synchronous re-check for the moderator's «второе мнение» (§38.2), volunteer path. */
    public void recheckVolunteer(int volId, String documentPath, String applicantName) {
        runVolunteerKycCheck(volId, documentPath, applicantName);
    }

    void runKycCheck(int needyId, String documentPath, String applicantName) {
        try {
            if (!Files.isRegularFile(Paths.get(documentPath))) {
                return;
            }
            String ext = extension(documentPath);
            String mime = MIME_BY_EXT.getOrDefault(ext, "image/jpeg");
            byte[] content = crypto.readDecrypted(documentPath);
            JsonNode parsed = analyze(content, mime, applicantName, SYSTEM_PROMPT);
            if (parsed == null) {
                // AI unavailable → stay pending; the Python kyc_retry_tick re-runs it later.
                saveResult(needyId, null, VERDICT_UNCHECKED,
                    "ИИ-проверка недоступна, будет повторена автоматически");
                return;
            }
            Scored result = score(parsed);
            // Persist the verdict FIRST, so the row is never auto-approved with a null KYC record.
            saveResult(needyId, result.score, result.verdict, result.notes);
            if (VERDICT_OK.equals(result.verdict)) {
                if (autoApprove(needyId, result.score)) {
                    saveResult(needyId, result.score, result.verdict,
                        truncate("[авто-одобрено ИИ] " + result.notes));
                }
            }
            // Hybrid: likely_fraud / review / unchecked are NOT auto-rejected —
            // they stay 'pending' for a human moderator (ethical: the AI never
            // refuses a vulnerable applicant on its own).
            log.info("[kyc] needy " + needyId + ": " + result.verdict + " (" + result.score + ")");
        } catch (Exception e) {
            log.warning("[kyc] needy check for " + needyId + " failed: " + e.getMessage());
        }
    }

    // ── Volunteer identity KYC (§58) — same fully-automated pipeline, no human ──

    /** Fire-and-forget entry point, the analogue of {@code start_volunteer_kyc_check}. */
    public void startVolunteerKycCheck(int volId, String documentPath, String applicantName) {
        pool.submit(() -> runVolunteerKycCheck(volId, documentPath, applicantName));
    }

    void runVolunteerKycCheck(int volId, String documentPath, String applicantName) {
        try {
            if (!Files.isRegularFile(Paths.get(documentPath))) {
                return;
            }
            String mime = MIME_BY_EXT.getOrDefault(extension(documentPath), "image/jpeg");
            byte[] content = crypto.readDecrypted(documentPath);
            JsonNode parsed = analyze(content, mime, applicantName, VOLUNTEER_SYSTEM_PROMPT);
            if (parsed == null) {
                volunteerRepo.saveVolunteerKyc(volId, null, VERDICT_UNCHECKED,
                    "ИИ-проверка недоступна, будет повторена автоматически");
                return;
            }
            Scored result = scoreVolunteer(parsed);
            volunteerRepo.saveVolunteerKyc(volId, result.score, result.verdict, result.notes);
            if (VERDICT_OK.equals(result.verdict)) {
                if (autoApproveVolunteer(volId, result.score)) {
                    volunteerRepo.saveVolunteerKyc(volId, result.score, result.verdict,
                        truncate("[авто-одобрено ИИ] " + result.notes));
                }
            }
            // Hybrid: likely_fraud / review / unchecked stay 'pending' for a human
            // moderator — the AI never rejects an identity document on its own.
            log.info("[kyc] volunteer " + volId + ": " + result.verdict + " (" + result.score + ")");
        } catch (Exception e) {
            log.warning("[kyc] volunteer check for " + volId + " failed: " + e.getMessage());
        }
    }

    /** Deterministic scoring of the AI's structured answer for an identity doc. */
    private Scored scoreVolunteer(JsonNode parsed) {
        if (!parsed.path("is_document").asBoolean(false) || !parsed.path("is_id_document").asBoolean(false)) {
            return new Scored(0.0, VERDICT_FRAUD,
                "На фото не распознано удостоверение личности. " + parsed.path("summary").asText(""));
        }
        List<String> notes = new ArrayList<>();
        double score = 0.6;
        JsonNode nameMatches = parsed.get("name_matches");
        if (nameMatches != null && nameMatches.isBoolean()) {
            if (nameMatches.asBoolean()) {
                score += 0.25;
            } else {
                score -= 0.35;
                notes.add("имя в документе не совпадает с анкетой");
            }
        }
        if (!parsed.path("legible").asBoolean(false)) {
            score -= 0.25;
            notes.add("документ плохо читаем");
        }
        if (parsed.path("tampering_signs").asBoolean(false)) {
            score -= 0.5;
            String reason = parsed.path("tampering_reason").asText("");
            notes.add("признаки редактирования: " + (reason.isBlank() ? "без деталей" : reason));
        }
        return finalize(score, notes, parsed);
    }

    private boolean autoApproveVolunteer(int volId, double score) {
        if (volunteerRepo.setVolunteerStatus(volId, STATUS_APPROVED, STATUS_PENDING) == null) {
            log.info("[kyc] volunteer " + volId + " no longer pending; skipping auto-approve");
            return false;
        }
        audit.log("auto-kyc", "kyc_auto_approve", "volunteer", volId,
            String.format("Auto-approved volunteer by AI KYC (score %.2f)", score));
        String msg = "Ваш аккаунт волонтёра подтверждён автоматической проверкой — можно брать маршруты.";
        jdbc.update(
            "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
            volId, "moderation_approved", msg, OffsetDateTime.now());
        try {
            telegram.notifyVolunteer(volId, "✅ " + msg);
        } catch (RuntimeException ignore) {
            // best-effort, like the Python try/except: pass
        }
        log.info("[kyc] volunteer " + volId + " auto-approved (score " + score + ")");
        return true;
    }

    private JsonNode analyze(byte[] content, String mime, String applicantName, String systemPrompt) {
        if (apiKey == null || apiKey.isBlank() || content == null || content.length == 0) {
            return null;
        }
        try {
            Map<String, Object> inlineData = new LinkedHashMap<>();
            inlineData.put("mime_type", mime);
            inlineData.put("data", Base64.getEncoder().encodeToString(content));
            Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(
                        Map.of("inline_data", inlineData),
                        Map.of("text", "Имя заявителя в анкете: "
                            + (applicantName == null || applicantName.isBlank() ? "не указано" : applicantName))))),
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
                log.warning("[kyc] Gemini API " + resp.statusCode() + ": "
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
            String json = stripFence(text.toString().strip());
            JsonNode parsed = mapper.readTree(json);
            return parsed.isObject() ? parsed : null;
        } catch (Exception e) {
            log.warning("[kyc] document analysis failed: " + e.getMessage());
            return null;
        }
    }

    /** Deterministic scoring of the AI's structured answer (0=fraud, 1=ok). */
    private Scored score(JsonNode parsed) {
        if (!parsed.path("is_document").asBoolean(false)) {
            return new Scored(0.0, VERDICT_FRAUD,
                "На фото не распознан документ. " + parsed.path("summary").asText(""));
        }
        List<String> notes = new ArrayList<>();
        double score = 0.5;
        if (parsed.path("supports_need").asBoolean(false)) {
            score += 0.3;
        } else {
            score -= 0.3;
            notes.add("документ не подтверждает нуждаемость");
        }
        JsonNode nameMatches = parsed.get("name_matches");
        if (nameMatches != null && nameMatches.isBoolean()) {
            if (nameMatches.asBoolean()) {
                score += 0.2;
            } else {
                score -= 0.3;
                notes.add("имя в документе не совпадает с анкетой");
            }
        }
        if (!parsed.path("legible").asBoolean(false)) {
            score -= 0.2;
            notes.add("текст плохо читаем");
        }
        if (parsed.path("tampering_signs").asBoolean(false)) {
            score -= 0.4;
            String reason = parsed.path("tampering_reason").asText("");
            notes.add("признаки редактирования: " + (reason.isBlank() ? "без деталей" : reason));
        }
        return finalize(score, notes, parsed);
    }

    private Scored finalize(double score, List<String> notes, JsonNode parsed) {
        score = Math.max(0.0, Math.min(1.0, Math.round(score * 100.0) / 100.0));
        String verdict = score >= okThreshold ? VERDICT_OK
            : score <= fraudThreshold ? VERDICT_FRAUD : VERDICT_REVIEW;
        String summary = parsed.path("summary").asText("");
        String docType = parsed.path("document_type").asText("");
        String prefix = docType.isBlank() || "null".equals(docType) ? "" : "[" + docType + "] ";
        String tail = String.join("; ", notes);
        String note = (prefix + summary + (tail.isBlank() ? "" : " — " + tail)).strip();
        return new Scored(score, verdict, truncate(note));
    }

    private void saveResult(int needyId, Double score, String verdict, String notes) {
        jdbc.update(
            "UPDATE needy SET kyc_score = ?, kyc_verdict = ?, kyc_notes = ?, kyc_checked_at = ? WHERE id = ?",
            score, verdict, notes, OffsetDateTime.now(), needyId);
    }

    /**
     * Approve without a human: conditional flip (still pending) + audit + in-app
     * notification. The document is retained encrypted-at-rest. Returns true only
     * if we approved.
     */
    private boolean autoApprove(int needyId, double score) {
        if (repo.setNeedyStatus(needyId, STATUS_APPROVED, STATUS_PENDING) == null) {
            log.info("[kyc] needy " + needyId + " no longer pending; skipping auto-approve");
            return false;
        }
        audit.log("auto-kyc", "kyc_auto_approve", "needy", needyId,
            String.format("Auto-approved by AI KYC (score %.2f)", score));
        String msg = "Ваша анкета одобрена автоматической проверкой — можете создавать заявки на получение продуктов.";
        jdbc.update(
            "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
            needyId, "moderation_approved", msg, OffsetDateTime.now());
        try {
            telegram.notifyNeedy(needyId, "✅ " + msg);
        } catch (RuntimeException ignore) {
            // best-effort, like the Python try/except: pass
        }
        log.info("[kyc] needy " + needyId + " auto-approved (score " + score + ")");
        return true;
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

    private record Scored(double score, String verdict, String notes) {
    }
}
