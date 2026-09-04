package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.savefood.background.MaintenanceTasks;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.PasswordService;
import ru.savefood.util.Qr;
import ru.savefood.volunteer.RoutePointPrivacy;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerController;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.volunteer.VolunteerService;
import ru.savefood.web.ApiException;
class RouteRecipientPrivacyIT extends PostgresIT {
    private final ObjectMapper mapper = new ObjectMapper();
    private NeedyService needyService;
    private VolunteerRepository volunteerRepo;
    private VolunteerService volunteerService;
    @BeforeEach
    void wire() {
        needyService = new NeedyService(jdbc, new NeedyRepository(jdbc), new PasswordService());
        volunteerRepo = new VolunteerRepository(jdbc);
        volunteerService = new VolunteerService(jdbc, volunteerRepo, new RouteRevertService(jdbc),
            new PasswordService(), needyService, null, "Europe/Moscow");
    }
    @Test
    void activeAssignedRouteSupportsNavigationThenFulfilmentScrubsPersistentAndHistoryData() {
        RouteFixture fixture = startRoute();
        Map<String, Object> active = volunteerService.activeRoute(fixture.volunteerId(), true);
        Map<String, Object> activeTicket = ticketPoint(points(active));
        assertThat(((Number) activeTicket.get("lat")).doubleValue()).isCloseTo(43.24,
            org.assertj.core.data.Offset.offset(0.00001));
        assertThat(((Number) activeTicket.get("lon")).doubleValue()).isCloseTo(76.90,
            org.assertj.core.data.Offset.offset(0.00001));
        assertThat(activeTicket).containsEntry("address", "Private street 7")
            .containsEntry("apartment", "17")
            .containsEntry("floor_num", "4")
            .containsEntry("entrance", "2")
            .containsEntry("items", "private dietary request");
        assertThat(activeTicket).doesNotContainKeys("contact", "phone", "needy_id", "available_time");
        volunteerService.completePoint(volunteerRepo.getRouteById(fixture.routeId()), fixture.volunteerId(),
            null, 43.238, 76.889, null);
        jdbc.update("UPDATE tickets SET delivery_photo = '/delivery_photos/proof.jpg', "
            + "delivery_photo_status = 'pending' WHERE id = ?", fixture.ticketId());
        String secret = jdbc.queryForObject("SELECT qr_secret FROM tickets WHERE id = ?",
            String.class, fixture.ticketId());
        volunteerService.completePoint(volunteerRepo.getRouteById(fixture.routeId()), fixture.volunteerId(),
            fixture.ticketId(), 43.24, 76.90, Qr.buildCode(fixture.ticketId(), secret));
        assertTerminalPointRedacted(ticketPoint(persistedPoints(fixture.routeId())));
        assertTerminalPointRedacted(ticketPoint(points(volunteerService.historyRoutes(
            fixture.volunteerId(), 20, 0).get(0))));
        assertThat(status("tickets", fixture.ticketId())).isEqualTo("fulfilled");
    }
    @Test
    void cancellationAndPermanentFailureScrubTheirRoutePoints() {
        RouteFixture cancelled = startRoute();
        needyService.cancelTicket(cancelled.needyId(), cancelled.ticketId());
        Map<String, Object> cancelledPoint = ticketPoint(persistedPoints(cancelled.routeId()));
        assertThat(cancelledPoint).containsEntry("done", true).containsEntry("cancelled", true);
        assertTerminalPointRedacted(cancelledPoint);
        RouteFixture failed = startRoute();
        volunteerService.completePoint(volunteerRepo.getRouteById(failed.routeId()), failed.volunteerId(),
            null, 43.238, 76.889, null);
        Map<String, Object> first = volunteerService.attemptDelivery(
            volunteerRepo.getRouteById(failed.routeId()), failed.volunteerId(), failed.ticketId(), 43.24, 76.90);
        Map<String, Object> second = volunteerService.attemptDelivery(
            volunteerRepo.getRouteById(failed.routeId()), failed.volunteerId(), failed.ticketId(), 43.24, 76.90);
        assertThat(first).containsEntry("attempt_count", 1).containsEntry("released", false);
        assertThat(second).containsEntry("attempt_count", 2).containsEntry("released", false);
        assertThat(status("tickets", failed.ticketId())).isEqualTo("assigned");
        Map<String, Object> third = volunteerService.attemptDelivery(
            volunteerRepo.getRouteById(failed.routeId()), failed.volunteerId(), failed.ticketId(), 43.24, 76.90);
        assertThat(third).containsEntry("attempt_count", 3).containsEntry("released", true);
        assertThat(status("tickets", failed.ticketId())).isEqualTo("cancelled");
        assertThat(status("lots", failed.lotId())).isEqualTo("taken");
        assertThat(lotQuantity(failed.lotId())).isEqualTo(1.0);
        Map<String, Object> failedPoint = ticketPoint(persistedPoints(failed.routeId()));
        assertThat(failedPoint).containsEntry("done", true).containsEntry("cancelled", true)
            .containsEntry("attempt_count", 3);
        assertTerminalPointRedacted(failedPoint);
        Map<String, Object> map = volunteerService.mapPoints("Алматы", 100);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unavailable = (List<Map<String, Object>>) map.get("tickets");
        assertThat(unavailable).extracting(point -> point.get("ticket_id")).doesNotContain(failed.ticketId());
        assertThat((List<?>) map.get("shops")).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE needy_id = ? AND type = 'ticket_cancelled'",
            Integer.class, failed.needyId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE needy_id = ? AND type = 'delivery_attempted'",
            Integer.class, failed.needyId())).isEqualTo(2);
        assertThatThrownBy(() -> volunteerService.attemptDelivery(volunteerRepo.getRouteById(failed.routeId()),
            failed.volunteerId(), failed.ticketId(), 43.24, 76.90))
            .isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
        volunteerService.finishRoute(volunteerRepo.getRouteById(failed.routeId()));
        assertThat(lotQuantity(failed.lotId())).isEqualTo(1.0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE needy_id = ? AND type = 'ticket_cancelled'",
            Integer.class, failed.needyId())).isEqualTo(1);
        assertThatThrownBy(() -> volunteerService.finishRoute(volunteerRepo.getRouteById(failed.routeId())))
            .isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        assertThat(lotQuantity(failed.lotId())).isEqualTo(1.0);
        int replacementLot = insertLot(insertShop("Replacement store", 43.238, 76.889), 2.0, "Bakery");
        int replacement = needyService.createTicket(failed.needyId(), "replacement", "Private street 7",
            43.24, 76.90, null, replacementLot, null, null, null, false);
        assertThat(status("tickets", replacement)).isEqualTo("open");
    }
    @Test
    void successfulDeliveryAfterEarlierAttemptsStillFulfilsTheTicket() {
        RouteFixture fixture = startRoute();
        volunteerService.completePoint(volunteerRepo.getRouteById(fixture.routeId()), fixture.volunteerId(),
            null, 43.238, 76.889, null);
        volunteerService.attemptDelivery(volunteerRepo.getRouteById(fixture.routeId()), fixture.volunteerId(),
            fixture.ticketId(), 43.24, 76.90);
        volunteerService.attemptDelivery(volunteerRepo.getRouteById(fixture.routeId()), fixture.volunteerId(),
            fixture.ticketId(), 43.24, 76.90);
        jdbc.update("UPDATE tickets SET delivery_photo = '/delivery_photos/proof.jpg', "
            + "delivery_photo_status = 'pending' WHERE id = ?", fixture.ticketId());
        String secret = jdbc.queryForObject("SELECT qr_secret FROM tickets WHERE id = ?", String.class,
            fixture.ticketId());
        volunteerService.completePoint(volunteerRepo.getRouteById(fixture.routeId()), fixture.volunteerId(),
            fixture.ticketId(), 43.24, 76.90, Qr.buildCode(fixture.ticketId(), secret));
        assertThat(status("tickets", fixture.ticketId())).isEqualTo("fulfilled");
    }
    @Test
    void routeTerminationScrubsAllTicketPointsAndKeepsStatisticsStable() {
        RouteFixture fixture = startRoute();
        jdbc.update("UPDATE tickets SET status = 'fulfilled', fulfilled_at = NOW() WHERE id = ?",
            fixture.ticketId());
        volunteerService.finishRoute(volunteerRepo.getRouteById(fixture.routeId()));
        Map<String, Object> before = volunteerService.stats(fixture.volunteerId());
        Map<String, Object> point = ticketPoint(persistedPoints(fixture.routeId()));
        assertTerminalPointRedacted(point);
        point.put("lat", 43.24);
        point.put("address", "temporarily restored legacy PII");
        jdbc.update("UPDATE volunteer_routes SET points = ? WHERE id = ?",
            writePoints(persistedPointsWithReplacement(fixture.routeId(), point)), fixture.routeId());
        Map<String, Object> withLegacyPii = volunteerService.stats(fixture.volunteerId());
        jdbc.update("UPDATE volunteer_routes SET points = ? WHERE id = ?",
            RoutePointPrivacy.redactAllTicketPointsJson(
                jdbc.queryForObject("SELECT points FROM volunteer_routes WHERE id = ?", String.class,
                    fixture.routeId())), fixture.routeId());
        Map<String, Object> after = volunteerService.stats(fixture.volunteerId());
        assertThat(withLegacyPii).isEqualTo(before);
        assertThat(after).isEqualTo(withLegacyPii);
        assertThat(after).containsEntry("total_deliveries", 1);
        assertThat(((Number) after.get("total_kg")).doubleValue()).isPositive();
    }
    @Test
    void accountErasureScrubsEveryRouteCopyAssociatedWithRecipient() {
        RouteFixture fixture = startRoute();
        needyService.eraseAccount(fixture.needyId());
        Map<String, Object> point = ticketPoint(persistedPoints(fixture.routeId()));
        assertTerminalPointRedacted(point);
        assertThat(point).containsEntry("done", true).containsEntry("cancelled", true);
        assertThat(status("tickets", fixture.ticketId())).isEqualTo("cancelled");
        assertThat(status("needy", fixture.needyId())).isEqualTo("deleted");
    }
    @Test
    void historyApiAndActiveApiDoNotExposeRecipientDataToUnauthorizedActors() {
        RouteFixture fixture = startRoute();
        VolunteerController controller = controller();
        CurrentUser owner = new CurrentUser(11, "owner", "volunteer", fixture.volunteerId());
        CurrentUser other = new CurrentUser(12, "other", "volunteer", insertVolunteer("Other"));
        CurrentUser admin = new CurrentUser(1, "admin", "admin", null);
        Map<String, Object> adminActive = controller.activeRoute(fixture.volunteerId(), admin);
        assertTerminalPointRedacted(ticketPoint(points(adminActive)));
        assertThatThrownBy(() -> controller.activeRoute(fixture.volunteerId(), other))
            .isInstanceOf(ApiException.class).extracting("status").isEqualTo(403);
        List<Map<String, Object>> ownerHistory = controller.history(fixture.volunteerId(), 20, 0, owner);
        assertTerminalPointRedacted(ticketPoint(points(ownerHistory.get(0))));
        assertThatThrownBy(() -> controller.history(fixture.volunteerId(), 20, 0, other))
            .isInstanceOf(ApiException.class).extracting("status").isEqualTo(403);
        int otherVolunteer = other.relatedId();
        jdbc.update("UPDATE tickets SET assigned_volunteer_id = ? WHERE id = ?",
            otherVolunteer, fixture.ticketId());
        assertTerminalPointRedacted(ticketPoint(points(volunteerService.activeRoute(
            fixture.volunteerId(), true))));
    }
    @Test
    void migrationRedactsLegacyTerminalRowsAndTerminalPointsButKeepsActiveNavigation() throws Exception {
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .target("7").load().migrate();
        int terminalVolunteer = insertVolunteer("Legacy terminal");
        int activeVolunteer = insertVolunteer("Legacy active");
        int activeTicket = insertLegacyAssignedTicket(activeVolunteer);
        int activeDoneVolunteer = insertVolunteer("Legacy active done");
        int activeDoneTicket = insertLegacyAssignedTicket(activeDoneVolunteer);
        int terminalRoute = insertRawRoute(terminalVolunteer, "finished", legacyPoints(activeTicket, false));
        int activeRoute = insertRawRoute(activeVolunteer, "in_progress", legacyPoints(activeTicket, false));
        int activeDoneRoute = insertRawRoute(activeDoneVolunteer, "in_progress",
            legacyPoints(activeDoneTicket, true));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        assertThat(ticketPoint(persistedPoints(terminalRoute)))
            .doesNotContainKey("unknown_private_detail");
        assertTerminalPointRedacted(ticketPoint(persistedPoints(terminalRoute)));
        assertThat(ticketPoint(persistedPoints(activeRoute)))
            .containsEntry("lat", 43.24).containsEntry("address", "Legacy home")
            .containsEntry("unknown_private_detail", "must also disappear");
        assertThat(ticketPoint(persistedPoints(activeDoneRoute)))
            .doesNotContainKey("unknown_private_detail");
        assertTerminalPointRedacted(ticketPoint(persistedPoints(activeDoneRoute)));
    }
    @Test
    void timeoutTerminationScrubsPersistentRouteData() {
        RouteFixture fixture = startRoute();
        jdbc.update("UPDATE volunteer_routes SET started_at = NOW() - INTERVAL '100 minutes' WHERE id = ?",
            fixture.routeId());
        MaintenanceTasks maintenance = new MaintenanceTasks(jdbc, txManager, new RouteRevertService(jdbc),
            null, null, "embedded", "", "/tmp/savefood-route-privacy-it", 1, 0);
        maintenance.reassignTick();
        assertThat(status("volunteer_routes", fixture.routeId())).isEqualTo("timed_out");
        assertTerminalPointRedacted(ticketPoint(persistedPoints(fixture.routeId())));
    }
    private RouteFixture startRoute() {
        int shopId = insertShop("Store", 43.238, 76.889);
        int lotId = insertLot(shopId, 2.0, "Bakery");
        int needyId = insertNeedy("Recipient");
        int ticketId = needyService.createTicket(needyId, "private dietary request", "Private street 7",
            43.24, 76.90, "call after 18:00", lotId, "17", "4", "2", false);
        int volunteerId = insertVolunteer("Courier");
        VolunteerService.StartRouteResult route = volunteerService.startRoute(
            volunteerId, Map.of("name", "Courier", "lat", 43.238, "lon", 76.889,
                "has_thermal_bag", true), lotId, 1);
        return new RouteFixture(route.routeId(), volunteerId, needyId, ticketId, lotId);
    }
    private VolunteerController controller() {
        return new VolunteerController(volunteerRepo, volunteerService, null, null, null, null, null,
            null, null, jdbc, null, true, "/tmp", "/tmp");
    }
    private int insertRawRoute(int volunteerId, String status, String points) {
        return jdbc.queryForObject(
            "INSERT INTO volunteer_routes (volunteer_id, points, status, started_at, finished_at) "
                + "VALUES (?, ?, ?, NOW(), CASE WHEN ? = 'in_progress' THEN NULL ELSE NOW() END) RETURNING id",
            Integer.class, volunteerId, points, status, status);
    }
    private int insertLegacyAssignedTicket(int volunteerId) {
        int needyId = insertNeedy("Legacy recipient");
        return jdbc.queryForObject(
            "INSERT INTO tickets (needy_id, items, address, lat, lon, status, assigned_volunteer_id, "
                + "created_at) VALUES (?, 'private request', 'Legacy home', 43.24, 76.90, "
                + "'assigned', ?, NOW()) RETURNING id",
            Integer.class, needyId, volunteerId);
    }
    private String legacyPoints(int ticketId, boolean done) {
        return """
            [{"kind":"shop","lat":43.238,"lon":76.889,"description":"Store"},
             {"kind":"ticket","ticket_id":%d,"lat":43.24,"lon":76.90,
              "address":"Legacy home","apartment":"9","floor_num":"3","entrance":"1",
              "door_code":"1234","contact":"+70000000000","items":"private request",
              "description":"private request","addr_detail":"unit 9",
              "unknown_private_detail":"must also disappear","done":%s}]
            """.formatted(ticketId, done);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> points(Map<String, Object> route) {
        return (List<Map<String, Object>>) route.get("points");
    }
    private List<Map<String, Object>> persistedPoints(int routeId) {
        try {
            String json = jdbc.queryForObject("SELECT points FROM volunteer_routes WHERE id = ?",
                String.class, routeId);
            return mapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
    private List<Map<String, Object>> persistedPointsWithReplacement(int routeId,
                                                                      Map<String, Object> replacement) {
        List<Map<String, Object>> points = persistedPoints(routeId);
        for (int i = 0; i < points.size(); i++) {
            if ("ticket".equals(points.get(i).get("kind"))) {
                points.set(i, replacement);
            }
        }
        return points;
    }
    private String writePoints(List<Map<String, Object>> points) {
        try {
            return mapper.writeValueAsString(points);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
    private Map<String, Object> ticketPoint(List<Map<String, Object>> points) {
        return points.stream().filter(point -> "ticket".equals(point.get("kind")))
            .findFirst().orElseThrow();
    }
    private void assertTerminalPointRedacted(Map<String, Object> point) {
        assertThat(point.keySet()).doesNotContainAnyElementsOf(RoutePointPrivacy.SENSITIVE_TICKET_FIELDS);
        assertThat(point).containsKeys("kind", "ticket_id");
    }
    private record RouteFixture(int routeId, int volunteerId, int needyId, int ticketId, int lotId) {
    }
}
