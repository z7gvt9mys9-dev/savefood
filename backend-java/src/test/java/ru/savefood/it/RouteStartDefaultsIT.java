package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.volunteer.VolunteerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
/** Contract coverage for an omitted route stop limit. */
class RouteStartDefaultsIT extends PostgresIT {
    private NeedyService needyService;
    private VolunteerService volunteerService;
    @BeforeEach
    void wire() {
        needyService = new NeedyService(jdbc, new NeedyRepository(jdbc), new PasswordService());
        volunteerService = new VolunteerService(jdbc, new VolunteerRepository(jdbc), new RouteRevertService(jdbc),
            new PasswordService(), needyService, null, "Europe/Moscow");
    }
    @Test
    void omittedMaxStopsUsesBackendDefaultForAndroidAndWebFlows() {
        RouteOutcome android = startRouteWithoutCapacityChoice("Android");
        RouteOutcome web = startRouteWithoutCapacityChoice("Web");
        assertThat(android).isEqualTo(new RouteOutcome(15, 15, 0));
        assertThat(web).isEqualTo(android);
    }
    @Test
    void explicitMaxStopsStillLimitsRoute() {
        int volunteerId = insertVolunteer("Волонтёр");
        int lotId = createLotWithEligibleTickets(15);
        VolunteerService.StartRouteResult route = volunteerService.startRoute(
            volunteerId, volunteer(volunteerId), lotId, 5);
        assertThat(ticketPointCount(route.points())).isEqualTo(5);
        assertThat(ticketStatusCount(lotId, "assigned")).isEqualTo(5);
        assertThat(ticketStatusCount(lotId, "cancelled")).isEqualTo(10);
    }
    private RouteOutcome startRouteWithoutCapacityChoice(String volunteerName) {
        int volunteerId = insertVolunteer(volunteerName);
        int lotId = createLotWithEligibleTickets(15);
        VolunteerService.StartRouteResult route = volunteerService.startRoute(
            volunteerId, volunteer(volunteerId), lotId, null);
        return new RouteOutcome(
            ticketPointCount(route.points()),
            ticketStatusCount(lotId, "assigned"),
            ticketStatusCount(lotId, "cancelled"));
    }
    private int createLotWithEligibleTickets(int count) {
        int lotId = insertLot(insertShop("Магазин", 43.238, 76.889), 20.0, "Выпечка");
        IntStream.range(0, count).forEach(i -> {
            int needyId = insertNeedy("Получатель " + i);
            needyService.createTicket(needyId, "хлеб", "адрес", 43.24, 76.90,
                null, lotId, null, null, null, false);
        });
        return lotId;
    }
    private Map<String, Object> volunteer(int volunteerId) {
        return Map.of(
            "id", volunteerId,
            "name", "Волонтёр",
            "lat", 43.238,
            "lon", 76.889,
            "has_thermal_bag", true);
    }
    private int ticketStatusCount(int lotId, String status) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM tickets WHERE lot_id = ? AND status = ?", Integer.class, lotId, status);
    }
    private static int ticketPointCount(List<Map<String, Object>> points) {
        return (int) points.stream().filter(point -> "ticket".equals(point.get("kind"))).count();
    }
    private record RouteOutcome(int routeTickets, int assignedTickets, int cancelledTickets) {
    }
}
