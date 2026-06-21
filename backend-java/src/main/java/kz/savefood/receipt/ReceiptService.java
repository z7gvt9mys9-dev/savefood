package kz.savefood.receipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Receipt OCR + item classification + anti-fraud (Gemini Vision), ported from
 * receipt_service.py. One Vision call extracts merchant/date/total/items and maps
 * each item onto the four lot categories; deterministic rules then score the
 * receipt for fraud before any lot is created. Parsed receipts are modelled as
 * {@code Map<String,Object>} to mirror the Python dicts the callers consume.
 */
@Service
public class ReceiptService {

    private static final Logger log = Logger.getLogger(ReceiptService.class.getName());

    public static final List<String> LOT_CATEGORIES =
        List.of("Выпечка", "Овощи/Фрукты", "Готовая еда", "Молочные продукты");

    public static final double FRAUD_REJECT_THRESHOLD = 0.7;
    public static final double FRAUD_FLAG_THRESHOLD = 0.4;

    private static final String SYSTEM_PROMPT = """
        Ты — система распознавания кассовых чеков платформы SaveFood (Казахстан).
        На фото — чек списания продуктов из магазина (язык: русский/казахский).

        Верни СТРОГО один JSON-объект без пояснений:
        {
          "is_receipt": true|false,
          "merchant": "название магазина или null",
          "receipt_date": "YYYY-MM-DD или null",
          "total": число или null,
          "currency": "KZT" или другая валюта, или null,
          "items": [
            {
              "raw_name": "позиция как напечатана в чеке",
              "name": "короткое чистое название на русском",
              "category": "одна из: Выпечка, Овощи/Фрукты, Готовая еда, Молочные продукты",
              "quantity": число,
              "unit": "kg" | "pcs" | "l",
              "weight_kg": оценка веса позиции в килограммах (число)
            }
          ],
          "authenticity": "ok" | "suspicious",
          "authenticity_reason": "почему suspicious, иначе null"
        }

        Правила:
        - is_receipt=false, если на фото не кассовый/товарный чек.
        - category — ближайшая из четырёх; напитки и бакалею относи к "Готовая еда".
        - weight_kg: для штучных товаров оцени типичный вес (батон ~0.4, йогурт ~0.3).
        - Непродовольственные позиции (пакеты, бытовая химия) пропускай.
        - authenticity="suspicious", если чек выглядит отредактированным, нарисованным,
          сфотографированным с экрана, со «скачущими» шрифтами или замазанными полями.
        """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60)).build();

    private final String apiKey;
    private final String model;
    private final int maxAgeHours;

    public ReceiptService(@Value("${savefood.gemini-api-key:}") String apiKey,
                          @Value("${savefood.ocr-model:gemini-2.5-flash}") String model,
                          @Value("${savefood.receipt-max-age-hours:48}") int maxAgeHours) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxAgeHours = maxAgeHours;
    }

    /**
     * One Vision call: OCR + normalization + classification + authenticity.
     *
     * @return the parsed map, or {@code null} when the AI stage is unavailable
     *         or failed (no key, network error, unparseable answer).
     */
    public Map<String, Object> parseReceiptImage(byte[] content, String mimeType) {
        if (apiKey == null || apiKey.isBlank() || content == null || content.length == 0) {
            return null;
        }
        try {
            Map<String, Object> inlineData = new LinkedHashMap<>();
            inlineData.put("mime_type", mimeType == null || mimeType.isBlank() ? "image/jpeg" : mimeType);
            inlineData.put("data", Base64.getEncoder().encodeToString(content));
            Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(
                        Map.of("inline_data", inlineData),
                        Map.of("text", "Распознай этот чек.")))),
                "generationConfig", Map.of(
                    "responseMimeType", "application/json",
                    "maxOutputTokens", 4000));

            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(60))
                .header("x-goog-api-key", apiKey)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warning("[ocr] Gemini API " + resp.statusCode() + ": "
                    + resp.body().substring(0, Math.min(200, resp.body().length())));
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
            // responseMimeType=application/json normally yields bare JSON, but be
            // defensive about ```json fences anyway.
            if (text.startsWith("```")) {
                text = text.replaceAll("^`+", "").replaceAll("`+$", "");
                if (text.startsWith("json")) {
                    text = text.substring(4);
                }
            }
            JsonNode parsed = mapper.readTree(text);
            if (!parsed.isObject()) {
                return null;
            }
            return sanitize(parsed);
        } catch (Exception e) {
            log.warning("[ocr] receipt parse failed: " + e);
            return null;
        }
    }

    /** Coerce model output into the shapes the rest of the code relies on. */
    private Map<String, Object> sanitize(JsonNode parsed) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode it : parsed.path("items")) {
            if (!it.isObject()) {
                continue;
            }
            String name = textOrEmpty(it, "name");
            if (name.isEmpty()) {
                name = textOrEmpty(it, "raw_name");
            }
            name = name.strip();
            if (name.isEmpty()) {
                continue;
            }
            String category = it.path("category").asText(null);
            if (!LOT_CATEGORIES.contains(category)) {
                category = "Готовая еда";
            }
            double weight = 0.0;
            JsonNode w = it.path("weight_kg");
            if (w.isNumber()) {
                weight = Math.max(0.0, w.asDouble());
            } else if (w.isTextual()) {
                try {
                    weight = Math.max(0.0, Double.parseDouble(w.asText()));
                } catch (NumberFormatException ignored) {
                    weight = 0.0;
                }
            }
            String rawName = textOrEmpty(it, "raw_name");
            if (rawName.isBlank()) {
                rawName = name;
            }
            String unit = it.path("unit").asText(null);
            if (!("kg".equals(unit) || "pcs".equals(unit) || "l".equals(unit))) {
                unit = "pcs";
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("raw_name", rawName.strip());
            item.put("name", name);
            item.put("category", category);
            item.put("quantity", it.has("quantity") && it.get("quantity").isNumber()
                ? it.get("quantity").numberValue() : null);
            item.put("unit", unit);
            item.put("weight_kg", round2(weight));
            items.add(item);
        }

        LocalDate receiptDate = null;
        String rawDate = parsed.path("receipt_date").asText(null);
        if (rawDate != null && !rawDate.isBlank() && !"null".equals(rawDate)) {
            try {
                receiptDate = LocalDate.parse(rawDate.substring(0, Math.min(10, rawDate.length())));
            } catch (RuntimeException ignored) {
                receiptDate = null;
            }
        }
        Double total = null;
        JsonNode totalNode = parsed.path("total");
        if (totalNode.isNumber()) {
            total = totalNode.asDouble();
        } else if (totalNode.isTextual()) {
            try {
                total = Double.parseDouble(totalNode.asText());
            } catch (NumberFormatException ignored) {
                total = null;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("is_receipt", parsed.path("is_receipt").asBoolean(false));
        out.put("merchant", emptyToNull(parsed.path("merchant").asText(null)));
        out.put("receipt_date", receiptDate);
        out.put("total", total);
        out.put("currency", emptyToNull(parsed.path("currency").asText(null)));
        out.put("items", items);
        out.put("authenticity", "suspicious".equals(parsed.path("authenticity").asText(null))
            ? "suspicious" : "ok");
        out.put("authenticity_reason", emptyToNull(parsed.path("authenticity_reason").asText(null)));
        return out;
    }

    public String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** merchant|date|total — catches the same receipt re-photographed. */
    @SuppressWarnings("unchecked")
    public String fingerprint(Map<String, Object> parsed) {
        Object date = parsed.get("receipt_date");
        Object total = parsed.get("total");
        if (date == null || total == null) {
            return null;
        }
        String merchant = parsed.get("merchant") == null ? ""
            : ((String) parsed.get("merchant")).strip().toLowerCase(Locale.ROOT);
        return merchant + "|" + ((LocalDate) date) + "|"
            + String.format(Locale.ROOT, "%.2f", ((Number) total).doubleValue());
    }

    /** Deterministic scoring on top of the AI verdict. */
    public Map<String, Object> evaluateFraud(Map<String, Object> parsed, boolean fingerprintDupe) {
        double score = 0.0;
        List<String> reasons = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        LocalDate rdate = (LocalDate) parsed.get("receipt_date");
        if (rdate == null) {
            score += 0.3;
            reasons.add("дата чека не распознана");
        } else if (rdate.isAfter(today)) {
            score += 0.8;
            reasons.add("дата чека в будущем (" + rdate + ")");
        } else if (rdate.isBefore(today.minusDays(maxAgeHours / 24))) {
            score += 0.8;
            reasons.add("чек старше " + maxAgeHours + " ч (" + rdate + ")");
        }

        if (fingerprintDupe) {
            score += 0.9;
            reasons.add("дубликат: чек с тем же магазином, датой и суммой уже загружался");
        }

        if ("suspicious".equals(parsed.get("authenticity"))) {
            score += 0.5;
            Object reason = parsed.get("authenticity_reason");
            reasons.add("ИИ: " + (reason == null ? "признаки редактирования изображения" : reason));
        }

        score = Math.min(1.0, round2(score));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("reasons", reasons);
        out.put("rejected", score >= FRAUD_REJECT_THRESHOLD);
        out.put("flagged", score >= FRAUD_FLAG_THRESHOLD);
        return out;
    }

    /**
     * Group parsed items by category into lot drafts the shop can confirm — one
     * lot per category (not per line), so a 15-line receipt can't flood the map.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> suggestLots(List<Map<String, Object>> items) {
        Map<String, List<String>> names = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            String category = (String) it.get("category");
            String name = (String) it.get("name");
            List<String> ns = names.computeIfAbsent(category, k -> new ArrayList<>());
            if (!ns.contains(name)) {
                ns.add(name);
            }
            Object w = it.get("weight_kg");
            weights.merge(category, w instanceof Number n ? n.doubleValue() : 0.0, Double::sum);
        }
        List<Map<String, Object>> drafts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : names.entrySet()) {
            String category = e.getKey();
            // lots.quantity is integer kg; never suggest 0 for a non-empty group.
            int quantity = Math.max(1, (int) Math.round(weights.getOrDefault(category, 0.0)));
            String desc = String.join(", ", e.getValue());
            if (desc.length() > 300) {
                desc = desc.substring(0, 300);
            }
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("category", category);
            draft.put("description", desc);
            draft.put("quantity", quantity);
            drafts.add(draft);
        }
        return drafts;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : "";
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static double round2(double x) {
        return BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
    }
}
