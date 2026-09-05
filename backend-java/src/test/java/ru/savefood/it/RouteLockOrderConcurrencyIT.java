package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.util.Qr;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.volunteer.VolunteerService;
import ru.savefood.web.ApiException;

/** Real PostgreSQL waits, with latches at the old inversion boundaries; no timing sleeps. */
class RouteLockOrderConcurrencyIT extends PostgresIT {
    private NeedyService needy;
    private VolunteerService volunteer;
    private VolunteerRepository routes;
    private RouteRevertService revert;
    private ObservedJdbc observed;
    private ExecutorService executor;
    private final ThreadLocal<Boolean> firstWorker = ThreadLocal.withInitial(() -> false);
    private volatile Predicate<String> pauseAfter = sql -> false;
    private CountDownLatch paused;
    private CountDownLatch release;

    @BeforeEach
    void wire() {
        observed = new ObservedJdbc();
        needy = new NeedyService(observed, new NeedyRepository(observed), new PasswordService());
        routes = new VolunteerRepository(observed);
        revert = new RouteRevertService(observed);
        volunteer = new VolunteerService(observed, routes, revert, new PasswordService(), needy,
            null, "Europe/Moscow");
        executor = Executors.newFixedThreadPool(2);
        paused = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    @AfterEach
    void stop() throws Exception {
        release.countDown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    @RepeatedTest(3)
    void startWinsWhileCancellationWaitsBeforeTakingTheTicket() throws Exception {
        Fixture f = reservation();
        // Old code let cancellation hold ticket while waiting on this lot.
        List<Object> results = race(sql -> sql.startsWith("SELECT * FROM lots") && locking(sql),
            () -> start(f), () -> needy.cancelTicket(f.needy(), f.ticket()));
        assertThat(results.get(0)).isInstanceOf(VolunteerService.StartRouteResult.class);
        assertDomainError(results.get(1), 409);
        assertThat(status("tickets", f.ticket())).isEqualTo("assigned");
        assertThat(jdbc.queryForObject("SELECT assigned_volunteer_id FROM tickets WHERE id = ?",
            Integer.class, f.ticket())).isEqualTo(f.volunteer());
        assertThat(status("lots", f.lot())).isEqualTo("taken");
        assertThat(lotQuantity(f.lot())).isEqualTo(2);
        assertThat(((VolunteerService.StartRouteResult) results.get(0)).assignedNeedy())
            .singleElement().satisfies(pair -> assertThat(pair).containsExactly(f.needy(), f.ticket()));
    }

    @RepeatedTest(3)
    void cancellationWinsAndStaleStartReturnsTheExistingStateError() throws Exception {
        Fixture f = reservation();
        // Old cancellation held only the ticket here; start then held lot and waited ticket.
        List<Object> results = race(sql -> sql.startsWith("SELECT * FROM tickets WHERE id") && locking(sql),
            () -> needy.cancelTicket(f.needy(), f.ticket()), () -> start(f));
        assertThat(results.get(0)).isEqualTo("ok");
        assertDomainError(results.get(1), 400);
        assertThat(status("tickets", f.ticket())).isEqualTo("cancelled");
        assertThat(status("lots", f.lot())).isEqualTo("active");
        assertThat(lotQuantity(f.lot())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_routes", Integer.class)).isZero();
    }

    @RepeatedTest(3)
    void completionWinsAndErasureScrubsTheCommittedDelivery() throws Exception {
        Fixture f = delivery();
        // Old erasure updated ticket before blocking on this route, completing the cycle.
        List<Object> results = race(sql -> sql.startsWith("SELECT * FROM volunteer_routes") && locking(sql),
            () -> complete(f), () -> needy.eraseAccount(f.needy()));
        assertThat(results.get(0)).isEqualTo("ok");
        assertThat(results.get(1)).isInstanceOf(NeedyService.EraseResult.class);
        assertThat(((NeedyService.EraseResult) results.get(1)).photos()).containsExactly("/private/proof.jpg");
        assertThat(status("tickets", f.ticket())).isEqualTo("fulfilled");
        assertErased(f);
    }

    @RepeatedTest(3)
    void erasureWinsAndStaleCompletionCannotRestorePrivateData() throws Exception {
        Fixture f = delivery();
        List<Object> results = race(sql -> sql.startsWith("SELECT id, points FROM volunteer_routes") && locking(sql),
            () -> needy.eraseAccount(f.needy()), () -> complete(f));
        assertThat(results.get(0)).isInstanceOf(NeedyService.EraseResult.class);
        assertDomainError(results.get(1), 400);
        assertThat(status("tickets", f.ticket())).isEqualTo("cancelled");
        assertErased(f);
    }

    @Test
    void erasureDiscoversRouteCommittedAfterItsTicketPreselection() throws Exception {
        Fixture f = reservation();
        List<Object> results = race(sql -> sql.startsWith("SELECT * FROM lots") && locking(sql),
            () -> start(f), () -> needy.eraseAccount(f.needy()));
        assertThat(results.get(0)).isInstanceOf(VolunteerService.StartRouteResult.class);
        assertThat(results.get(1)).isInstanceOf(NeedyService.EraseResult.class);
        assertThat(status("tickets", f.ticket())).isEqualTo("cancelled");
        assertErased(f);
    }

    @Test
    void concurrentDuplicateCancellationRestoresInventoryOnlyOnce() throws Exception {
        Fixture f = reservation();
        List<Object> results = race(sql -> sql.startsWith("SELECT * FROM tickets WHERE id") && locking(sql),
            () -> needy.cancelTicket(f.needy(), f.ticket()), () -> needy.cancelTicket(f.needy(), f.ticket()));
        assertThat(results.get(0)).isEqualTo("ok");
        assertDomainError(results.get(1), 400);
        assertThat(lotQuantity(f.lot())).isEqualTo(3);
        assertThat(status("tickets", f.ticket())).isEqualTo("cancelled");
    }

    @Test
    void erasureAndRouteFinishAlsoAcquireLotBeforeRoute() throws Exception {
        Fixture f = reservation();
        int routeId = tx.execute(s -> start(f)).routeId();
        Map<String, Object> staleRoute = routes.getRouteById(routeId);
        List<Object> results = race(sql -> sql.startsWith("SELECT id FROM lots") && locking(sql),
            () -> needy.eraseAccount(f.needy()), () -> {
                volunteer.finishRoute(staleRoute);
                return null;
            });
        assertThat(results.get(0)).isInstanceOf(NeedyService.EraseResult.class);
        assertThat(results.get(1)).isEqualTo("ok");
        assertThat(status("volunteer_routes", routeId)).isEqualTo("finished");
        assertThat(status("lots", f.lot())).isEqualTo("active");
        assertThat(lotQuantity(f.lot())).isEqualTo(3);
        assertThat(status("tickets", f.ticket())).isEqualTo("cancelled");
        assertErased(f);
    }

    @Test
    void erasureLocksLotsRoutesAndTicketsByIdEvenWithReversePhysicalAndAssociationOrder() {
        int shop = insertShop("Shop", 43.238, 76.889);
        int lowLot = insertLot(shop, 3, "Bakery");
        int highLot = insertLot(shop, 3, "Bakery");
        int recipient = insertNeedy("Private recipient");
        // Legacy history can have many tickets and route copies for one recipient.
        for (int id : List.of(200, 100)) {
            jdbc.update("INSERT INTO tickets (id, needy_id, lot_id, status, quantity, address, created_at) "
                + "VALUES (?, ?, ?, 'fulfilled', 1, 'Private street', NOW())", id, recipient,
                id == 100 ? highLot : lowLot);
            jdbc.update("INSERT INTO volunteer_routes (id, volunteer_id, lot_id, status, points, started_at) "
                + "VALUES (?, ?, ?, 'finished', ?, NOW())", id, insertVolunteer("Volunteer"),
                id == 100 ? highLot : lowLot,
                "[{\"kind\":\"ticket\",\"ticket_id\":" + id + ",\"address\":\"Private street\"}]");
        }
        observed.locks.clear();
        tx.executeWithoutResult(s -> needy.eraseAccount(recipient));
        assertThat(observed.locks).containsExactly(
            "lots:[" + lowLot + "]", "lots:[" + highLot + "]",
            "volunteer_routes:[100, 200]", "tickets:[100, 200]");
        assertThat(jdbc.queryForList("SELECT points FROM volunteer_routes", String.class))
            .allSatisfy(points -> assertThat(points).doesNotContain("Private street"));
    }

    @Test
    void startLocksAllOpenTicketsBeforeApplyingStopOrderOrBulkCancellation() {
        Fixture f = reservation();
        int second = createTicket(insertNeedy("Second"), f.lot(), false);
        int pickup = createTicket(insertNeedy("Pickup"), f.lot(), true);
        observed.locks.clear();
        VolunteerService.StartRouteResult result = tx.execute(s -> volunteer.startRoute(f.volunteer(),
            routes.getVolunteerById(f.volunteer()), f.lot(), 1));
        assertThat(observed.locks).containsExactly("lots:[" + f.lot() + "]",
            "tickets:[" + f.ticket() + ", " + second + ", " + pickup + "]");
        assertThat(result.assignedNeedy()).hasSize(1);
        assertThat(status("tickets", result.assignedNeedy().get(0)[1])).isEqualTo("assigned");
        assertThat(status("tickets", pickup)).isEqualTo("cancelled");
    }

    @Test
    void revertLocksTicketsByIdRatherThanRouteVisitOrderAndDeduplicatesThem() {
        Fixture f = reservation();
        int second = createTicket(insertNeedy("Second"), f.lot(), false);
        jdbc.update("UPDATE tickets SET status = 'assigned', assigned_volunteer_id = ? WHERE lot_id = ?",
            f.volunteer(), f.lot());
        String points = "[{\"kind\":\"shop\",\"done\":true},"
            + "{\"kind\":\"ticket\",\"ticket_id\":" + second + "},"
            + "{\"kind\":\"ticket\",\"ticket_id\":" + f.ticket() + "},"
            + "{\"kind\":\"ticket\",\"ticket_id\":" + second + "}]";
        observed.locks.clear();
        tx.executeWithoutResult(s -> revert.revertRouteLot(f.lot(), points));
        assertThat(observed.locks).containsExactly("tickets:[" + f.ticket() + "]", "tickets:[" + second + "]");
        assertThat(status("tickets", f.ticket())).isEqualTo("cancelled");
        assertThat(status("tickets", second)).isEqualTo("cancelled");
    }

    private Fixture reservation() {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 3, "Bakery");
        int recipient = insertNeedy("Private recipient");
        int ticket = createTicket(recipient, lot, false);
        return new Fixture(lot, recipient, ticket, insertVolunteer("Volunteer"), null, null);
    }

    private int createTicket(int recipient, int lot, boolean pickup) {
        return tx.execute(s -> needy.createTicket(recipient, "Private items", "Private street", 43.24,
            76.90, "Private time", lot, "Private apartment", "Private floor", "Private entrance", pickup));
    }

    private VolunteerService.StartRouteResult start(Fixture f) {
        return volunteer.startRoute(f.volunteer(), routes.getVolunteerById(f.volunteer()), f.lot(), null);
    }

    private Fixture delivery() {
        Fixture f = reservation();
        int route = tx.execute(s -> start(f)).routeId();
        tx.executeWithoutResult(s -> volunteer.completePoint(routes.getRouteById(route), f.volunteer(),
            null, 43.238, 76.889, null));
        jdbc.update("UPDATE tickets SET delivery_photo = '/private/proof.jpg', delivery_photo_status = 'pending' "
            + "WHERE id = ?", f.ticket());
        String secret = jdbc.queryForObject("SELECT qr_secret FROM tickets WHERE id = ?", String.class, f.ticket());
        return new Fixture(f.lot(), f.needy(), f.ticket(), f.volunteer(), routes.getRouteById(route),
            Qr.buildCode(f.ticket(), secret));
    }

    private Object complete(Fixture f) {
        volunteer.completePoint(f.route(), f.volunteer(), f.ticket(), 43.24, 76.90, f.qr());
        return null;
    }

    private void assertErased(Fixture f) {
        assertThat(status("needy", f.needy())).isEqualTo("deleted");
        Map<String, Object> ticket = jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", f.ticket());
        for (String field : List.of("items", "address", "lat", "lon", "apartment", "floor_num", "entrance",
                "available_time", "delivery_photo")) {
            assertThat(ticket.get(field)).as(field).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM needy_profile WHERE needy_id = ?",
            Integer.class, f.needy())).isZero();
        List<String> points = jdbc.queryForList("SELECT points FROM volunteer_routes", String.class);
        assertThat(points).isNotEmpty().allSatisfy(json -> assertThat(json)
            .doesNotContain("Private", "43.24", "76.9", "proof.jpg"));
    }

    private static void assertDomainError(Object result, int status) {
        assertThat(result).isInstanceOf(ApiException.class);
        assertThat(((ApiException) result).getStatus()).isEqualTo(status);
    }

    private List<Object> race(Predicate<String> boundary, Supplier<?> first, Supplier<?> second) throws Exception {
        pauseAfter = boundary;
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        CountDownLatch secondStarted = new CountDownLatch(1);
        Future<Object> firstResult = executor.submit(() -> {
            firstWorker.set(true);
            return outcome(first, firstPid, null);
        });
        try {
            await(paused);
            Future<Object> secondResult = executor.submit(() -> outcome(second, secondPid, secondStarted));
            await(secondStarted);
            awaitBlockedBy(secondPid.get(), firstPid.get());
            release.countDown();
            return List.of(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private Object outcome(Supplier<?> operation, AtomicInteger pid, CountDownLatch started) {
        try {
            return tx.execute(s -> {
                jdbc.execute("SET LOCAL statement_timeout = '12s'");
                pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                if (started != null) {
                    started.countDown();
                }
                Object result = operation.get();
                return result == null ? "ok" : result;
            });
        } catch (ApiException e) {
            return e; // Outside the transaction: losing domain operations must roll back.
        }
    }

    private void awaitBlockedBy(int waiter, int holder) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",
                    Boolean.class, holder, waiter))) {
                return;
            }
        }
        throw new AssertionError("PostgreSQL worker " + waiter + " never waited for " + holder);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).as("race latch").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static boolean locking(String sql) {
        return sql.contains("FOR UPDATE");
    }

    private class ObservedJdbc extends JdbcTemplate {
        private final List<String> locks = java.util.Collections.synchronizedList(new ArrayList<>());

        ObservedJdbc() {
            super(dataSource);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            List<Map<String, Object>> rows = super.queryForList(sql, args);
            if (locking(sql)) {
                for (String table : List.of("lots", "volunteer_routes", "tickets")) {
                    if (sql.contains("FROM " + table + " ")) {
                        List<Object> ids = rows.stream().map(row -> row.containsKey("id") ? row.get("id") : args[0]).toList();
                        locks.add(table + ":" + ids);
                    }
                }
            }
            if (firstWorker.get() && pauseAfter.test(sql)) {
                pauseAfter = ignored -> false;
                paused.countDown();
                await(release);
            }
            return rows;
        }
    }

    private record Fixture(int lot, int needy, int ticket, int volunteer, Map<String, Object> route, String qr) { }
}
