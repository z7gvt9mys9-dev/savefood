package ru.savefood.receipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ReceiptServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant DAYTIME = Instant.parse("2026-01-02T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 2);
    /** Service with default thresholds (48h max age); no API key — AI stage unused here. */
    private final ReceiptService service = serviceAt(DAYTIME, "Europe/Moscow");
    private static ReceiptService serviceAt(Instant instant, String zone) {
        return new ReceiptService("", "gemini-2.5-flash", 48,
            Clock.fixed(instant, ZoneId.of(zone)));
    }
    /** Mirrors _parsed() from the pytest module: a clean receipt parsed today. */
    private static Map<String, Object> parsed(Map<String, Object> overrides) {
        Map<String, Object> base = new HashMap<>();
        base.put("receipt_date", TODAY);
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
        LocalDate future = TODAY.plusDays(2);
        Map<String, Object> fraud =
            service.evaluateFraud(parsed(Map.of("receipt_date", future)), false);
        assertThat(fraud.get("rejected")).isEqualTo(true);
    }
    @Test
    void staleDateRejects() {
        LocalDate old = TODAY.minusDays(10);
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
        LocalDate old = TODAY.minusDays(10);
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
    void localSaturdayReceiptIsTodayWhileUtcIsStillFriday() {
        Instant boundary = Instant.parse("2026-01-02T21:30:00Z");
        Clock moscowClock = Clock.fixed(boundary, ZoneId.of("Europe/Moscow"));
        LocalDate saturday = LocalDate.of(2026, 1, 3);
        assertThat(LocalDate.now(moscowClock)).isEqualTo(saturday);
        assertThat(LocalDate.now(moscowClock.withZone(ZoneOffset.UTC))).isEqualTo(saturday.minusDays(1));
        assertThat(serviceAt(boundary, "Europe/Moscow")
            .evaluateFraud(parsed(Map.of("receipt_date", saturday)), false))
            .containsEntry("score", 0.0).containsEntry("rejected", false);
    }
    @Test
    void normalDaytimeFraudScoreIsUnchangedAcrossZones() {
        Map<String, Object> receipt = parsed(Map.of("receipt_date", TODAY));
        assertThat(serviceAt(DAYTIME, "UTC").evaluateFraud(receipt, false).get("score"))
            .isEqualTo(serviceAt(DAYTIME, "Europe/Moscow").evaluateFraud(receipt, false).get("score"))
            .isEqualTo(0.0);
    }
    @Test
    void configuredTimezoneChangesReceiptBusinessDateConsistently() {
        Instant boundary = Instant.parse("2026-01-02T23:30:00Z");
        Map<String, Object> saturdayReceipt = parsed(Map.of("receipt_date", LocalDate.of(2026, 1, 3)));
        assertThat(serviceAt(boundary, "UTC").evaluateFraud(saturdayReceipt, false).get("score"))
            .isEqualTo(0.8);
        assertThat(serviceAt(boundary, "Asia/Tokyo").evaluateFraud(saturdayReceipt, false).get("score"))
            .isEqualTo(0.0);
    }
    @Test
    void dstObservingZoneUsesIanaRules() {
        Instant before = Instant.parse("2026-03-08T06:30:00Z");
        Instant after = Instant.parse("2026-03-08T07:30:00Z");
        ZoneId newYork = ZoneId.of("America/New_York");
        assertThat(newYork.getRules().getOffset(before)).isNotEqualTo(newYork.getRules().getOffset(after));
        Map<String, Object> receipt = parsed(Map.of("receipt_date", LocalDate.of(2026, 3, 8)));
        assertThat(serviceAt(before, newYork.getId()).evaluateFraud(receipt, false).get("score"))
            .isEqualTo(0.0);
        assertThat(serviceAt(after, newYork.getId()).evaluateFraud(receipt, false).get("score"))
            .isEqualTo(0.0);
    }
    @Test
    @SuppressWarnings("unchecked")
    void suggestLotsGroupsByCategory() {
        List<Map<String, Object>> items = List.of(
            Map.of("name", "Батон", "category", "Выпечка", "weight_kg", 0.4),
            Map.of("name", "Круассан", "category", "Выпечка", "weight_kg", 0.3),
            Map.of("name", "Кефир", "category", "Молочные продукты", "weight_kg", 1.0));
        List<Map<String, Object>> drafts = service.suggestLots(items);
        assertThat(drafts).hasSize(2);
        Map<String, Object> bakery = drafts.stream()
            .filter(d -> "Выпечка".equals(d.get("category")))
            .findFirst().orElseThrow();
        assertThat((String) bakery.get("description")).contains("Батон").contains("Круассан");
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
        assertThat(items).hasSize(2);
        assertThat(items.get(1).get("category")).isEqualTo("Готовая еда");
        assertThat(items.get(1).get("weight_kg")).isEqualTo(0.0);
        assertThat(out.get("authenticity")).isEqualTo("ok");
    }
}
