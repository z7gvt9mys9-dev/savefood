package ru.savefood.esg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class EsgServiceTest {

    @Mock
    JdbcTemplate jdbc;

    EsgService service;

    @BeforeEach
    void setup() {
        service = new EsgService(jdbc);
    }

    private static Map<String, Object> catRow(String category, double kg, int lots) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", category);
        m.put("kg", kg);
        m.put("lots", lots);
        return m;
    }

    private static Map<String, Object> monthRow(String month, double kg, double co2) {
        return Map.of("month", month, "kg", kg, "co2_kg", co2);
    }

    // Stub two sequential queryForList(String, Object...) calls: first→catRows, second→monthRows.
    // anyString() + any(Integer.class) × 2 pins us to the Object-vararg overload and avoids
    // the ambiguity with queryForList(String, Object[], int[]).
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubShopReport(List catRows, List monthRows) {
        int[] call = {0};
        doAnswer(inv -> call[0]++ == 0 ? catRows : monthRows)
            .when(jdbc).queryForList(
                anyString(),
                org.mockito.ArgumentMatchers.any(Integer.class),
                org.mockito.ArgumentMatchers.any(Integer.class));
    }

    @Test
    void buildReportTotals() {
        var catRows = List.of(
            catRow("Выпечка", 10.0, 2),
            catRow(null, 4.0, 1)  // null category → default CO2
        );
        var monthRows = List.of(monthRow("2026-05", 14.0, 23.0));
        stubShopReport(catRows, monthRows);

        Map<String, Object> report = service.shopReport(1, 12);
        Map<?, ?> totals = (Map<?, ?>) report.get("totals");

        assertThat(totals.get("kg")).isEqualTo(14.0);
        // Выпечка: 10*1.3=13.0; default: 4*2.5=10.0; total=23.0
        assertThat(totals.get("co2_kg")).isEqualTo(23.0);
        assertThat(totals.get("lots")).isEqualTo(3);
        // 14 / 0.42 = 33 meals
        assertThat((int) totals.get("meals")).isEqualTo(33);

        @SuppressWarnings("unchecked")
        var byCat = (List<Map<?, ?>>) report.get("by_category");
        assertThat(byCat.get(1).get("category")).isEqualTo("Без категории");

        @SuppressWarnings("unchecked")
        var byMonth = (List<Map<?, ?>>) report.get("by_month");
        assertThat(byMonth.get(0).get("month")).isEqualTo("2026-05");
    }

    @Test
    void buildReportEmpty() {
        stubShopReport(List.of(), List.of());

        Map<String, Object> report = service.shopReport(1, 6);
        Map<?, ?> totals = (Map<?, ?>) report.get("totals");

        assertThat(totals.get("kg")).isEqualTo(0.0);
        assertThat(totals.get("co2_kg")).isEqualTo(0.0);
        assertThat(totals.get("lots")).isEqualTo(0);
        assertThat(((List<?>) report.get("by_category"))).isEmpty();
    }

    @Test
    void reportToCsvContainsTotalsAndBreakdown() {
        // Build a report map matching the structure that shopReport produces.
        Map<String, Object> totals = Map.of(
            "kg", 14.0, "co2_kg", 23.0, "meals", 33, "lots", 3);
        Map<String, Object> catBakery = Map.of(
            "category", "Выпечка", "kg", 10.0, "co2_kg", 13.0, "lots", 2);
        Map<String, Object> catNone = Map.of(
            "category", "Без категории", "kg", 4.0, "co2_kg", 10.0, "lots", 1);
        Map<String, Object> month = Map.of(
            "month", "2026-05", "kg", 14.0, "co2_kg", 23.0);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("methodology", EsgService.METHODOLOGY);
        report.put("period_months", 12);
        report.put("totals", totals);
        report.put("by_category", List.of(catBakery, catNone));
        report.put("by_month", List.of(month));

        String csv = service.reportToCsv(report, "Тест-Маркет");

        assertThat(csv).contains("Тест-Маркет");
        assertThat(csv).contains("Итого, кг");
        assertThat(csv).contains("Выпечка");
        assertThat(csv).contains("Без категории");
        assertThat(csv).contains("2026-05");
    }

    @Test
    void monthsClampedTo1And36() {
        stubShopReport(List.of(), List.of());
        Map<String, Object> r1 = service.shopReport(1, 0);
        assertThat(r1.get("period_months")).isEqualTo(1);

        stubShopReport(List.of(), List.of());
        Map<String, Object> r2 = service.shopReport(1, 100);
        assertThat(r2.get("period_months")).isEqualTo(36);
    }
}
