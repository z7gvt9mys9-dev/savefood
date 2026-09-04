package ru.savefood.forecast;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
@ExtendWith(MockitoExtension.class)
class ForecastServiceTest {
    @Mock
    JdbcTemplate jdbc;
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
    @Test
    void groupsFridayUtcInstantAsSaturdayInConfiguredMoscowTimezone() {
        Instant fridayUtc = Instant.parse("2026-01-02T21:30:00Z");
        assertThat(ForecastService.isoDowAt(fridayUtc, ZoneId.of("Europe/Moscow"))).isEqualTo(6);
    }
    @Test
    void mapsInstantsOnEitherSideOfLocalMidnightToTheirLocalWeekdays() {
        ZoneId moscow = ZoneId.of("Europe/Moscow");
        assertThat(ForecastService.isoDowAt(Instant.parse("2026-01-02T20:59:59Z"), moscow)).isEqualTo(5);
        assertThat(ForecastService.isoDowAt(Instant.parse("2026-01-02T21:00:00Z"), moscow)).isEqualTo(6);
    }
    @Test
    void usesConfiguredTimezoneForSqlGroupingAndJavaDayCalculation() {
        when(jdbc.queryForList(anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        ForecastService service = new ForecastService(jdbc, "Europe/Moscow");
        service.shopForecast(42);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> timezone = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).queryForList(sql.capture(), timezone.capture(),
            org.mockito.ArgumentMatchers.eq(42), org.mockito.ArgumentMatchers.eq(8));
        assertThat(sql.getValue()).contains("EXTRACT(ISODOW FROM l.created_at AT TIME ZONE ?)");
        assertThat(timezone.getValue()).isEqualTo("Europe/Moscow");
        assertThat(ForecastService.isoDowAt(Instant.parse("2026-01-02T21:30:00Z"),
            ZoneId.of(timezone.getValue()))).isEqualTo(6);
    }
    @Test
    void observesDstTimezoneRulesInsteadOfUsingFixedOffset() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant beforeTransition = Instant.parse("2026-03-08T06:59:59Z");
        Instant afterTransition = Instant.parse("2026-03-08T07:00:00Z");
        assertThat(beforeTransition.atZone(newYork).getHour()).isEqualTo(1);
        assertThat(afterTransition.atZone(newYork).getHour()).isEqualTo(3);
        assertThat(ForecastService.isoDowAt(beforeTransition, newYork)).isEqualTo(7);
        assertThat(ForecastService.isoDowAt(afterTransition, newYork)).isEqualTo(7);
    }
    @Test
    void changingConfiguredTimezoneChangesGroupingConsistently() {
        Instant instant = Instant.parse("2026-01-02T23:30:00Z");
        assertThat(ForecastService.isoDowAt(instant, ZoneId.of("UTC"))).isEqualTo(5);
        assertThat(ForecastService.isoDowAt(instant, ZoneId.of("Asia/Tokyo"))).isEqualTo(6);
    }
    @Test
    void daytimeTimestampKeepsItsWeekdayAcrossRelevantTimezones() {
        Instant daytimeUtc = Instant.parse("2026-01-02T12:00:00Z");
        assertThat(ForecastService.isoDowAt(daytimeUtc, ZoneId.of("UTC"))).isEqualTo(5);
        assertThat(ForecastService.isoDowAt(daytimeUtc, ZoneId.of("Europe/Moscow"))).isEqualTo(5);
        assertThat(ForecastService.isoDowAt(daytimeUtc, ZoneId.of("America/New_York"))).isEqualTo(5);
    }
    @Test
    void rejectsInvalidLocalTimezoneAtConstruction() {
        assertThatThrownBy(() -> new ForecastService(jdbc, "not/a-timezone"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("savefood.local-tz");
    }
}
