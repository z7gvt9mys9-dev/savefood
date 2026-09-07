package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import ru.savefood.background.MaintenanceTasks;
import ru.savefood.volunteer.RouteRevertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
/** Regression coverage for inactivity heartbeats versus the hard route limit. */
class RouteTimeoutActivityIT extends PostgresIT {
    private MaintenanceTasks maintenance;
    @BeforeEach
    void wire() {
        maintenance = new MaintenanceTasks(jdbc, txManager, new RouteRevertService(jdbc), null, null,
            "embedded", "", "/tmp/savefood-route-timeout-it", 1, 0);
    }
    @Test
    void regularHeartbeatsKeepRouteAlivePastInactivityThreshold() {
        int routeId = insertRoute(120, null, "in_progress");
        int volunteerId = volunteerForRoute(routeId);
        gpsHeartbeat(volunteerId);
        maintenance.reassignTick();
        assertThat(status("volunteer_routes", routeId)).isEqualTo("in_progress");
    }
    @Test
    void routeWithNoActivityTimesOut() {
        int routeId = insertRoute(91, null, "in_progress");
        maintenance.reassignTick();
        assertThat(status("volunteer_routes", routeId)).isEqualTo("timed_out");
    }
    @Test
    void hardMaximumDurationExpiresDespiteRecentHeartbeats() {
        int routeId = insertRoute(241, 0, "in_progress");
        maintenance.reassignTick();
        assertThat(status("volunteer_routes", routeId)).isEqualTo("timed_out");
    }
    @Test
    void timeoutReopensFullyReservedLotWithoutCreatingInventory() {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 1.0, "Bakery");
        int volunteer = insertVolunteer("Timed out courier");
        int needy = insertNeedy("Recipient");
        int ticket = jdbc.queryForObject(
            "INSERT INTO tickets (needy_id, items, lot_id, quantity, status, created_at, "
                + "assigned_volunteer_id) VALUES (?, 'food', ?, 1, 'assigned', NOW(), ?) RETURNING id",
            Integer.class, needy, lot, volunteer);
        jdbc.update("UPDATE lots SET quantity = 0, status = 'taken', taken_at = NOW() WHERE id = ?", lot);
        String points = """
            [{"kind":"shop","lat":43.238,"lon":76.889},
             {"kind":"ticket","ticket_id":%d,"lat":43.24,"lon":76.90}]
            """.formatted(ticket);
        int route = jdbc.queryForObject(
            "INSERT INTO volunteer_routes (volunteer_id, points, status, lot_id, started_at) "
                + "VALUES (?, ?::jsonb, 'in_progress', ?, CURRENT_TIMESTAMP - INTERVAL '91 minutes') "
                + "RETURNING id",
            Integer.class, volunteer, points, lot);

        maintenance.reassignTick();

        assertThat(status("volunteer_routes", route)).isEqualTo("timed_out");
        assertThat(status("lots", lot)).isEqualTo("active");
        assertThat(lotQuantity(lot)).isZero();
        assertThat(status("tickets", ticket)).isEqualTo("open");
        assertThat(jdbc.queryForObject(
            "SELECT assigned_volunteer_id FROM tickets WHERE id = ?", Integer.class, ticket)).isNull();
    }
    @Test
    void wrongVolunteerTerminalRouteAndNoRouteDoNotMutateRouteActivity() {
        int activeRoute = insertRoute(1, null, "in_progress");
        int finishedRoute = insertRoute(1, null, "finished");
        int cancelledRoute = insertRoute(1, null, "cancelled");
        int completedRoute = insertRoute(1, null, "completed");
        int otherVolunteer = insertVolunteer("Other volunteer");
        int noRouteVolunteer = insertVolunteer("No route volunteer");
        gpsHeartbeat(otherVolunteer);
        gpsHeartbeat(volunteerForRoute(finishedRoute));
        gpsHeartbeat(volunteerForRoute(cancelledRoute));
        gpsHeartbeat(volunteerForRoute(completedRoute));
        gpsHeartbeat(noRouteVolunteer);
        assertThat(lastActivity(activeRoute)).isNull();
        assertThat(lastActivity(finishedRoute)).isNull();
        assertThat(lastActivity(cancelledRoute)).isNull();
        assertThat(lastActivity(completedRoute)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_routes WHERE volunteer_id = ?",
            Integer.class, noRouteVolunteer)).isZero();
    }
    private int insertRoute(int startedMinutesAgo, Integer activityMinutesAgo, String status) {
        int volunteerId = insertVolunteer("Route volunteer");
        if (activityMinutesAgo == null) {
            return jdbc.queryForObject(
                "INSERT INTO volunteer_routes (volunteer_id, points, status, started_at, last_activity_at) "
                    + "VALUES (?, '[]', ?, CURRENT_TIMESTAMP - (? * INTERVAL '1 minute'), NULL) RETURNING id",
                Integer.class, volunteerId, status, startedMinutesAgo);
        }
        return jdbc.queryForObject(
            "INSERT INTO volunteer_routes (volunteer_id, points, status, started_at, last_activity_at) "
                + "VALUES (?, '[]', ?, CURRENT_TIMESTAMP - (? * INTERVAL '1 minute'), "
                + "CURRENT_TIMESTAMP - (? * INTERVAL '1 minute')) RETURNING id",
            Integer.class, volunteerId, status, startedMinutesAgo, activityMinutesAgo);
    }
    /** Same server-clock predicate used by the Go location writer. */
    private void gpsHeartbeat(int volunteerId) {
        jdbc.update("UPDATE volunteer_routes SET last_activity_at = CURRENT_TIMESTAMP "
            + "WHERE volunteer_id = ? AND status = 'in_progress'", volunteerId);
    }
    private int volunteerForRoute(int routeId) {
        return jdbc.queryForObject("SELECT volunteer_id FROM volunteer_routes WHERE id = ?", Integer.class, routeId);
    }
    private Object lastActivity(int routeId) {
        return jdbc.queryForObject("SELECT last_activity_at FROM volunteer_routes WHERE id = ?", Object.class, routeId);
    }
}
