package kz.savefood.forecast;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ForecastServiceTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> today(Map<String, Object> fc) {
        return (List<Map<String, Object>>) ((Map<?, ?>) fc.get("today")).get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tomorrow(Map<String, Object> fc) {
        return (List<Map<String, Object>>) ((Map<?, ?>) fc.get("tomorrow")).get("items");
    }

    @Test
    void averageOverFixedWeeks() {
        var rows = List.of(Map.<String, Object>of("isodow", 5, "category", "Выпечка", "kg", 96.0));
        var fc = ForecastService.buildForecast(rows, 5, 8);
        var items = today(fc);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("avg_kg")).isEqualTo(12.0);
        assertThat(tomorrow(fc)).isEmpty();
    }

    @Test
    void noiseBelowThresholdDropped() {
        // MIN_AVG_KG is 1.0; 7.9 kg / 8 weeks = 0.9875 < 1.0
        var rows = List.of(Map.<String, Object>of("isodow", 3, "category", "Молочные продукты", "kg", 7.9));
        var fc = ForecastService.buildForecast(rows, 3, 8);
        assertThat(today(fc)).isEmpty();
    }

    @Test
    void itemsSortedByVolumeDesc() {
        var rows = List.of(
            Map.<String, Object>of("isodow", 1, "category", "Выпечка", "kg", 16.0),
            Map.<String, Object>of("isodow", 1, "category", "Овощи/Фрукты", "kg", 80.0)
        );
        var fc = ForecastService.buildForecast(rows, 1, 8);
        var items = today(fc);
        assertThat(items.get(0).get("category")).isEqualTo("Овощи/Фрукты");
        assertThat(items.get(1).get("category")).isEqualTo("Выпечка");
    }

    @Test
    void sundayWrapsToMonday() {
        var fc = ForecastService.buildForecast(List.of(), 7, 8);
        assertThat(((Map<?, ?>) fc.get("tomorrow")).get("isodow")).isEqualTo(1);
        assertThat(((Map<?, ?>) fc.get("today")).get("day_name")).isEqualTo("воскресенье");
        assertThat(((Map<?, ?>) fc.get("tomorrow")).get("day_name")).isEqualTo("понедельник");
    }

    @Test
    void uncategorizedLotsStillForecast() {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("isodow", 2);
        row.put("category", null);
        row.put("kg", 24.0);
        var fc = ForecastService.buildForecast(List.of(row), 2, 8);
        var items = today(fc);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("category")).isEqualTo("Без категории");
        assertThat(items.get(0).get("avg_kg")).isEqualTo(3.0);
    }
}
