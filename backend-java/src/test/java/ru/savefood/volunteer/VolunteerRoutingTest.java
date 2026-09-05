package ru.savefood.volunteer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
class VolunteerRoutingTest {
    private static VolunteerService newService(String tz) {
        return new VolunteerService(null, null, null, null, null, null, tz);
    }
    private static Map<String, Object> t(int id, double lat, double lon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("lat", lat);
        m.put("lon", lon);
        return m;
    }
    private static List<Integer> ids(List<Map<String, Object>> order) {
        return order.stream().map(m -> (Integer) m.get("id")).toList();
    }
    @Test
    void haversineSymmetry() {
        double ab = VolunteerService.haversine(43.25, 76.95, 43.30, 76.90);
        double ba = VolunteerService.haversine(43.30, 76.90, 43.25, 76.95);
        assertThat(Math.abs(ab - ba)).isLessThan(1e-9);
    }
    @Test
    void optimizeKeepsAllStops() {
        var svc = newService("Europe/Moscow");
        double[] start = {0.0, 0.0};
        var tickets = List.of(t(1, 0.0, 0.3), t(2, 0.0, 0.1), t(3, 0.0, 0.2));
        var order = svc.optimizeStopOrder(tickets, start);
        assertThat(ids(order).stream().sorted().toList()).containsExactly(1, 2, 3);
    }
    @Test
    void optimizeOrdersCollinearPointsByDistance() {
        var svc = newService("Europe/Moscow");
        double[] start = {0.0, 0.0};
        var tickets = List.of(t(3, 0.0, 0.3), t(1, 0.0, 0.1), t(2, 0.0, 0.2));
        var order = svc.optimizeStopOrder(tickets, start);
        assertThat(ids(order)).containsExactly(1, 2, 3);
    }
    @Test
    void twoOptNoWorseThanAnyNaiveOrder() {
        var svc = newService("Europe/Moscow");
        double[] start = {0.0, 0.0};
        var tickets = List.of(t(1, 0.05, 0.2), t(2, -0.05, 0.1), t(3, 0.0, 0.3), t(4, 0.1, 0.05));
        double optimized = svc.routeLength(svc.optimizeStopOrder(tickets, start), start);
        double asGiven = svc.routeLength(tickets, start);
        assertThat(optimized).isLessThanOrEqualTo(asGiven + 1e-9);
    }
    @Test
    void singleTicketPassthrough() {
        var svc = newService("Europe/Moscow");
        var tickets = List.of(t(1, 0.0, 0.1));
        assertThat(svc.optimizeStopOrder(tickets, new double[]{0.0, 0.0})).isEqualTo(tickets);
    }
    @Test
    void routeExpiryUsesConfiguredBusinessDateAtLocalMidnight() {
        Instant fridayUtcSaturdayLocal = Instant.parse("2026-01-02T21:30:00Z");
        Clock clock = Clock.fixed(fridayUtcSaturdayLocal, ZoneId.of("Europe/Moscow"));
        LocalDate businessDate = LocalDate.now(clock);
        assertThat(businessDate).isEqualTo(LocalDate.of(2026, 1, 3));
        assertThat(VolunteerService.isRouteableExpiry(LocalDate.of(2026, 1, 4), businessDate)).isFalse();
        assertThat(VolunteerService.isRouteableExpiry(LocalDate.of(2026, 1, 5), businessDate)).isTrue();
        LocalDate oldUtcDate = LocalDate.now(clock.withZone(ZoneId.of("UTC")));
        assertThat(VolunteerService.isRouteableExpiry(LocalDate.of(2026, 1, 4), oldUtcDate)).isTrue();
    }
    @Test
    void routeMapPassesTheSameBusinessDateToBothSqlQueries() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T21:30:00Z"),
            ZoneId.of("Europe/Moscow"));
        VolunteerService service = new VolunteerService(jdbc, null, null, null, null, null, clock);

        service.mapPoints("Алматы", 100);

        assertThat(jdbc.businessDates).containsExactly(
            LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 3));
        assertThat(jdbc.sql).allMatch(query -> !query.contains("CURRENT_DATE"));
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private final List<LocalDate> businessDates = new ArrayList<>();
        private final List<String> sql = new ArrayList<>();

        @Override
        public List<Map<String, Object>> queryForList(String query, Object... args) {
            sql.add(query);
            for (Object arg : args) {
                if (arg instanceof LocalDate date) {
                    businessDates.add(date);
                }
            }
            return List.of();
        }
    }
}
