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
