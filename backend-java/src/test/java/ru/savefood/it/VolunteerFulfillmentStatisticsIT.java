package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.savefood.cache.CacheService;
import ru.savefood.esg.EsgService;
import ru.savefood.impact.ImpactController;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.volunteer.VolunteerService;

class VolunteerFulfillmentStatisticsIT extends PostgresIT {

    private NeedyService needy;
    private VolunteerRepository volunteers;
    private VolunteerService service;
    private EsgService esg;
    private ImpactController impact;

    @BeforeEach
    void wire() {
        needy = new NeedyService(jdbc, new NeedyRepository(jdbc), new PasswordService());
        volunteers = new VolunteerRepository(jdbc);
        service = new VolunteerService(jdbc, volunteers, new RouteRevertService(jdbc),
            new PasswordService(), needy, null, "Europe/Moscow");
        esg = new EsgService(jdbc);
        impact = new ImpactController(jdbc, esg, new CacheService(), "/tmp", "/tmp");
    }

    @Test
    void abandonedReclaimCyclesNeverReceiveLaterLotConfirmationCredit() {
        int abandonedVolunteer = insertVolunteer("Abandoned courier");
        int completingVolunteer = insertVolunteer("Completing courier");
        int abandonedTeam = insertTeam("Abandoned team");
        int completingTeam = insertTeam("Completing team");
        jdbc.update("UPDATE volunteers SET team_id = ? WHERE id = ?", abandonedTeam, abandonedVolunteer);
        jdbc.update("UPDATE volunteers SET team_id = ? WHERE id = ?", completingTeam, completingVolunteer);

        int lotId = insertLot(insertShop("Shop", 43.238, 76.889), 2.0, "Выпечка");
        int ticketId = needy.createTicket(insertNeedy("Recipient"), "food", "Address", 43.24, 76.90,
            null, lotId, null, null, null, false);

        int firstAbandoned = startRoute(abandonedVolunteer, lotId).routeId();
        service.finishRoute(volunteers.getRouteById(firstAbandoned));
        int secondAbandoned = startRoute(abandonedVolunteer, lotId).routeId();
        service.finishRoute(volunteers.getRouteById(secondAbandoned));
        assertThat(status("volunteer_routes", firstAbandoned)).isEqualTo("finished");
        assertThat(status("volunteer_routes", secondAbandoned)).isEqualTo("finished");

        int completedRoute = startRoute(completingVolunteer, lotId).routeId();
        jdbc.update("UPDATE tickets SET status = 'fulfilled', fulfilled_at = NOW() WHERE id = ?", ticketId);
        service.finishRoute(volunteers.getRouteById(completedRoute));
        // This is a later, legitimate hand-over fact. It must not rewrite either
        // abandoned route's historical contribution.
        jdbc.update("UPDATE lots SET status = 'confirmed' WHERE id = ?", lotId);

        assertStats(abandonedVolunteer, 0, 0.0);
        assertStats(completingVolunteer, 1, 1.0);
        assertTeam(impact.teamLeaderboard(), abandonedTeam, 0, 0.0);
        assertTeam(impact.teamLeaderboard(), completingTeam, 1, 1.0);
        assertThat(service.teamSummary(abandonedTeam)).containsEntry("deliveries", 0L)
            .containsEntry("kg", 0.0);
        assertThat(service.teamSummary(completingTeam)).containsEntry("deliveries", 1L)
            .containsEntry("kg", 1.0);
        List<Map<String, Object>> volunteerLeaderboard = impact.volunteerLeaderboard();
        assertThat(volunteerLeaderboard).extracting(row -> row.get("id"))
            .contains(completingVolunteer).doesNotContain(abandonedVolunteer);
        assertThat(volunteerLeaderboard).filteredOn(row -> ((Number) row.get("id")).intValue()
            == completingVolunteer).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("deliveries", 1).containsEntry("kg", 1.0));

        // Shop-confirmed ESG remains a single lot credit, not one credit per old route.
        assertThat(totals(esg.globalReport(12))).containsEntry("kg", 2.0)
            .containsEntry("meals", 4);
    }

    @Test
    void partialRouteCreditsOnlyItsFulfilledTicketsToVolunteerTeamAndEsg() {
        int volunteer = insertVolunteer("Partial courier");
        int team = insertTeam("Partial team");
        jdbc.update("UPDATE volunteers SET team_id = ? WHERE id = ?", team, volunteer);
        int lotId = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Выпечка");
        int first = needy.createTicket(insertNeedy("First"), "first", "Address", 43.24, 76.90,
            null, lotId, null, null, null, false);
        int second = needy.createTicket(insertNeedy("Second"), "second", "Address", 43.25, 76.91,
            null, lotId, null, null, null, false);
        jdbc.update("UPDATE tickets SET quantity = 2 WHERE id = ?", first);
        jdbc.update("UPDATE tickets SET quantity = 3 WHERE id = ?", second);

        int routeId = startRoute(volunteer, lotId).routeId();
        jdbc.update("UPDATE tickets SET status = 'fulfilled', fulfilled_at = NOW() WHERE id = ?", first);
        jdbc.update("UPDATE tickets SET status = 'cancelled' WHERE id = ?", second);
        service.finishRoute(volunteers.getRouteById(routeId));

        assertStats(volunteer, 1, 2.0);
        assertTeam(impact.teamLeaderboard(), team, 1, 2.0);
        assertThat(totals(esg.globalReport(12))).containsEntry("kg", 2.0)
            .containsEntry("meals", 4);
    }

    @Test
    void legacyFinishedRouteWithNoFulfilledTicketIsNeverInferredFromCurrentLotStatus() {
        int volunteer = insertVolunteer("Legacy courier");
        int lotId = insertLot(insertShop("Shop", 43.238, 76.889), 7.0, "Выпечка");
        jdbc.update("UPDATE lots SET status = 'confirmed' WHERE id = ?", lotId);
        jdbc.update("INSERT INTO volunteer_routes (volunteer_id, points, status, lot_id, started_at, finished_at) "
            + "VALUES (?, '[]', 'finished', ?, NOW(), NOW())", volunteer, lotId);

        assertStats(volunteer, 0, 0.0);
    }

    private VolunteerService.StartRouteResult startRoute(int volunteerId, int lotId) {
        return service.startRoute(volunteerId, Map.of("name", "Courier", "lat", 43.238, "lon", 76.889,
            "has_thermal_bag", true), lotId, 2);
    }

    private int insertTeam(String name) {
        return jdbc.queryForObject("INSERT INTO teams (name, join_code) VALUES (?, ?) RETURNING id",
            Integer.class, name, name + "-code");
    }

    private void assertStats(int volunteerId, int deliveries, double kg) {
        Map<String, Object> stats = service.stats(volunteerId);
        assertThat(stats).containsEntry("total_deliveries", deliveries).containsEntry("total_kg", kg);
    }

    private void assertTeam(List<Map<String, Object>> teams, int teamId, int deliveries, double kg) {
        assertThat(teams).filteredOn(team -> ((Number) team.get("id")).intValue() == teamId)
            .singleElement().satisfies(team -> assertThat(team).containsEntry("deliveries", (long) deliveries)
                .containsEntry("kg", kg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> totals(Map<String, Object> report) {
        return (Map<String, Object>) report.get("totals");
    }
}
