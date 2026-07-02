package kz.savefood.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receipt anti-fraud scoring and lot drafting (§32.2–32.3) — pure logic only.
 * Ported from tests/test_receipts.py.
 */
class ReceiptServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Service with default thresholds (48h max age); no API key — AI stage unused here. */
    private final ReceiptService service = new ReceiptService("", "gemini-2.5-flash", 48);

    /** Mirrors _parsed() from the pytest module: a clean receipt parsed today. */
    private static Map<String, Object> parsed(Map<String, Object> overrides) {
        Map<String, Object> base = new HashMap<>();
        base.put("receipt_date", LocalDate.now(ZoneOffset.UTC));
        base.put("merchant", "Magnum");
        base.put("total", 1234.0);
        base.put("authenticity", "ok");
        base.put("authenticity_reason", null);
        base.putAll(overrides);
        return base;
    }

    private static Map<String, Object> parsed() {
        return parsed(Map.of());
    }

    private static Map<String, Object> withNull(String key) {
        Map<String, Object> m = parsed();
        m.put(key, null);
        return m;
    }

    @Test
    void cleanReceiptPasses() {
        Map<String, Object> fraud = service.evaluateFraud(parsed(), false);
        assertThat(fraud.get("score")).isEqualTo(0.0);
        assertThat(fraud.get("rejected")).isEqualTo(false);
        assertThat(fraud.get("flagged")).isEqualTo(false);
    }

    @Test
    void missingDateIsSoftSignal() {
        Map<String, Object> fraud = service.evaluateFraud(withNull("receipt_date"), false);
        assertThat(fraud.get("score")).isEqualTo(0.3);
        assertThat(fraud.get("rejected")).isEqualTo(false);
    }

    @Test
    void futureDateRejects() {
        LocalDate future = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        Map<String, Object> fraud =
            service.evaluateFraud(parsed(Map.of("receipt_date", future)), false);
        assertThat(fraud.get("rejected")).isEqualTo(true);
    }

    @Test
    void staleDateRejects() {
        LocalDate old = LocalDate.now(ZoneOffset.UTC).minusDays(10);
        Map<String, Object> fraud =
            service.evaluateFraud(parsed(Map.of("receipt_date", old)), false);
        assertThat(fraud.get("rejected")).isEqualTo(true);
    }

    @Test
    void fingerprintDuplicateRejects() {
        Map<String, Object> fraud = service.evaluateFraud(parsed(), true);
        assertThat((double) fraud.get("score"))
            .isGreaterThanOrEqualTo(ReceiptService.FRAUD_REJECT_THRESHOLD);
        assertThat(fraud.get("rejected")).isEqualTo(true);
    }

    @Test
    void aiSuspicionAloneOnlyFlags() {
        Map<String, Object> fraud =
            service.evaluateFraud(parsed(Map.of("authenticity", "suspicious")), false);
        assertThat((double) fraud.get("score"))
            .isGreaterThanOrEqualTo(ReceiptService.FRAUD_FLAG_THRESHOLD)
            .isLessThan(ReceiptService.FRAUD_REJECT_THRESHOLD);
        assertThat(fraud.get("flagged")).isEqualTo(true);
        assertThat(fraud.get("rejected")).isEqualTo(false);
    }

    @Test
    void scoreCappedAtOne() {
        LocalDate old = LocalDate.now(ZoneOffset.UTC).minusDays(10);
        Map<String, Object> fraud = service.evaluateFraud(
            parsed(Map.of("receipt_date", old, "authenticity", "suspicious")), true);
        assertThat(fraud.get("score")).isEqualTo(1.0);
    }

    @Test
    void fingerprintRequiresDateAndTotal() {
        assertThat(service.fingerprint(withNull("receipt_date"))).isNull();
        assertThat(service.fingerprint(withNull("total"))).isNull();
        String fp = service.fingerprint(
            parsed(Map.of("receipt_date", LocalDate.of(2026, 6, 10), "total", 500.0)));
        assertThat(fp).isEqualTo("magnum|2026-06-10|500.00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void suggestLotsGroupsByCategory() {
        List<Map<String, Object>> items = List.of(
            Map.of("name", "Батон", "category", "Выпечка", "weight_kg", 0.4),
            Map.of("name", "Круассан", "category", "Выпечка", "weight_kg", 0.3),
            Map.of("name", "Кефир", "category", "Молочные продукты", "weight_kg", 1.0));
        List<Map<String, Object>> drafts = service.suggestLots(items);
        assertThat(drafts).hasSize(2);  // 3 line items → 2 lots, not 3
        Map<String, Object> bakery = drafts.stream()
            .filter(d -> "Выпечка".equals(d.get("category")))
            .findFirst().orElseThrow();
        assertThat((String) bakery.get("description")).contains("Батон").contains("Круассан");
        // integer kg, never 0 for a non-empty group
        assertThat((int) bakery.get("quantity")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sanitizeCoercesModelOutput() throws Exception {
        Map<String, Object> out = service.sanitize(MAPPER.readTree("""
            {
              "is_receipt": true,
              "receipt_date": "2026-06-10T00:00:00",
              "total": "1500",
              "items": [
                {"name": "Хлеб", "category": "Выпечка", "weight_kg": "0.4", "unit": "pcs"},
                {"name": "", "category": "Выпечка"},
                {"name": "Сок", "category": "Напитки", "weight_kg": -1}
              ],
              "authenticity": "definitely-fine"
            }
            """));
        assertThat(out.get("receipt_date")).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(out.get("total")).isEqualTo(1500.0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(2);  // empty-name item dropped
        assertThat(items.get(1).get("category")).isEqualTo("Готовая еда");  // unknown cat → fallback
        assertThat(items.get(1).get("weight_kg")).isEqualTo(0.0);  // negative clamped
        assertThat(out.get("authenticity")).isEqualTo("ok");  // unknown value → "ok"
    }
}
