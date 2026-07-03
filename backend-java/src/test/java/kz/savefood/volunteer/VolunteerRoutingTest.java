package kz.savefood.volunteer;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Port of tests/test_routing.py — route construction logic (§14): NN + 2-opt
 * ordering and the ticket time-window filter helpers.
 */
class VolunteerRoutingTest {

    /**
     * The tested helpers are pure logic: none of the collaborators is touched,
     * so no mocks are needed.
     */
    private static VolunteerService newService(String tz) {
        return new VolunteerService(null, null, null, null, null, null, tz);
    }

    /** The Python tests monkeypatched datetime.now() to 12:00 local; the Java
     * helpers take an explicit {@code now} instead. */
    private static final LocalTime NOON = LocalTime.of(12, 0);

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
        var svc = newService("Asia/Almaty");
        double[] start = {0.0, 0.0};
        var tickets = List.of(t(1, 0.0, 0.3), t(2, 0.0, 0.1), t(3, 0.0, 0.2));
        var order = svc.optimizeStopOrder(tickets, start);
        assertThat(ids(order).stream().sorted().toList()).containsExactly(1, 2, 3);
    }

    @Test
    void optimizeOrdersCollinearPointsByDistance() {
        // Points on a line east of the shop: any non-monotonic order is a zig-zag.
        var svc = newService("Asia/Almaty");
        double[] start = {0.0, 0.0};
        var tickets = List.of(t(3, 0.0, 0.3), t(1, 0.0, 0.1), t(2, 0.0, 0.2));
        var order = svc.optimizeStopOrder(tickets, start);
        assertThat(ids(order)).containsExactly(1, 2, 3);
    }

    @Test
    void twoOptNoWorseThanAnyNaiveOrder() {
        var svc = newService("Asia/Almaty");
        double[] start = {0.0, 0.0};
        var tickets = List.of(t(1, 0.05, 0.2), t(2, -0.05, 0.1), t(3, 0.0, 0.3), t(4, 0.1, 0.05));
        double optimized = svc.routeLength(svc.optimizeStopOrder(tickets, start), start);
        double asGiven = svc.routeLength(tickets, start);
        assertThat(optimized).isLessThanOrEqualTo(asGiven + 1e-9);
    }

    @Test
    void singleTicketPassthrough() {
        var svc = newService("Asia/Almaty");
        var tickets = List.of(t(1, 0.0, 0.1));
        assertThat(svc.optimizeStopOrder(tickets, new double[]{0.0, 0.0})).isEqualTo(tickets);
    }

    @Test
    void isAvailableNowWindow() {
        var svc = newService("Asia/Almaty");
        assertThat(svc.isAvailableNow("10:00-14:00", NOON)).isTrue();
        assertThat(svc.isAvailableNow("13:00-14:00", NOON)).isFalse();
        // overnight window 22:00-02:00 does not include noon
        assertThat(svc.isAvailableNow("22:00-02:00", NOON)).isFalse();
        // overnight window 09:00-01:00 includes noon
        assertThat(svc.isAvailableNow("09:00-01:00", NOON)).isTrue();
    }

    @Test
    void isAvailableNowLenientOnGarbage() {
        var svc = newService("Asia/Almaty");
        assertThat(svc.isAvailableNow("")).isTrue();
        assertThat(svc.isAvailableNow(null)).isTrue();
        assertThat(svc.isAvailableNow("not-a-window")).isTrue();
        assertThat(svc.isAvailableNow("10:00")).isTrue();
    }

    @Test
    void windowOpenWithinHorizon() {
        var svc = newService("Asia/Almaty");
        int h = 120;
        // open right now → true regardless of horizon
        assertThat(svc.windowOpenWithin("10:00-14:00", h, NOON)).isTrue();
        // opens in 60 min (13:00) → within the 120-min horizon → true (§59/Q1-A:
        // the working person whose window opens soon is no longer dropped+cancelled)
        assertThat(svc.windowOpenWithin("13:00-14:00", h, NOON)).isTrue();
        // opens in 180 min (15:00) → beyond the horizon → false
        assertThat(svc.windowOpenWithin("15:00-16:00", h, NOON)).isFalse();
        // already closed today, reopens tomorrow → false
        assertThat(svc.windowOpenWithin("08:00-09:00", h, NOON)).isFalse();
        // empty / unparseable → always available
        assertThat(svc.windowOpenWithin("", h, NOON)).isTrue();
        assertThat(svc.windowOpenWithin(null, h, NOON)).isTrue();
        // horizon 0 collapses to isAvailableNow (not open now → false)
        assertThat(svc.windowOpenWithin("13:00-14:00", 0, NOON)).isFalse();
    }
}
