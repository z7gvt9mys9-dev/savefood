package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.savefood.admin.AdminController;
import ru.savefood.audit.AuditService;
import ru.savefood.esg.EsgService;
import ru.savefood.security.CurrentUser;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.AvailabilityService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.web.ApiException;
/** PostgreSQL regressions for the guarded admin lot-recovery transition. */
class AdminLotResetIT extends PostgresIT {
    private static final CurrentUser ADMIN = new CurrentUser(1, "admin", "admin", null);
    private AdminController admin;
    private ExecutorService executor;
    @BeforeEach
    void wire() {
        admin = new AdminController(jdbc, new VolunteerRepository(jdbc), mock(EsgService.class),
            new AuditService(jdbc), mock(RouteRevertService.class), mock(AvailabilityService.class),
            mock(TelegramService.class), "/tmp", "/tmp");
        executor = Executors.newFixedThreadPool(2);
    }
    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }
    @Test
    void orphanedUnexpiredTakenLotResetsAndAuditsNormally() {
        int lot = takenLot();
        assertThat(admin.resetLot(lot, ADMIN)).containsEntry("ok", true);
        assertThat(status("lots", lot)).isEqualTo("active");
        assertThat(lotClaim(lot)).containsEntry("taken_by", null).containsEntry("taken_at", null);
        assertThat(resetAudits(lot)).isEqualTo(1);
    }
    @Test
    void resetPreservesAnExistingOpenReservation() {
        int lot = takenLot();
        int needy = insertNeedy("Recipient");
        int ticket = jdbc.queryForObject(
            "INSERT INTO tickets (needy_id, items, lot_id, quantity, status, created_at) "
            + "VALUES (?, 'food', ?, 1, 'open', NOW()) RETURNING id",
            Integer.class, needy, lot);
        jdbc.update("UPDATE lots SET quantity = quantity - 1 WHERE id = ?", lot);
        admin.resetLot(lot, ADMIN);
        assertThat(status("lots", lot)).isEqualTo("active");
        assertThat(status("tickets", ticket)).isEqualTo("open");
        assertThat(lotQuantity(lot)).isEqualTo(4.0);
    }
    @Test
    void confirmedLotCannotResetOrClearClaimFields() {
        int lot = takenLot();
        jdbc.update("UPDATE lots SET status = 'confirmed' WHERE id = ?", lot);
        assertResetRejected(lot);
        assertThat(status("lots", lot)).isEqualTo("confirmed");
        assertThat(lotClaim(lot)).containsEntry("taken_by", "Volunteer").containsKey("taken_at");
        assertThat(resetAudits(lot)).isZero();
    }
    @Test
    void removedLotCannotReset() {
        int lot = takenLot();
        jdbc.update("UPDATE lots SET status = 'removed' WHERE id = ?", lot);
        assertResetRejected(lot);
        assertThat(status("lots", lot)).isEqualTo("removed");
        assertThat(resetAudits(lot)).isZero();
    }
    @Test
    void expiredTakenLotCannotBecomeActive() {
        int lot = takenLot();
        jdbc.update("UPDATE lots SET expiry_date = CURRENT_DATE - 1 WHERE id = ?", lot);
        assertResetRejected(lot);
        assertThat(status("lots", lot)).isEqualTo("taken");
        assertThat(resetAudits(lot)).isZero();
    }
    @Test
    void lotWithAnActiveRouteCannotReset() {
        int lot = takenLot();
        int volunteer = insertVolunteer("Volunteer");
        jdbc.update("INSERT INTO volunteer_routes (volunteer_id, points, status, lot_id, started_at) "
            + "VALUES (?, '[]', 'in_progress', ?, NOW())", volunteer, lot);
        assertResetRejected(lot);
        assertThat(status("lots", lot)).isEqualTo("taken");
        assertThat(lotClaim(lot)).containsEntry("taken_by", "Volunteer");
        assertThat(resetAudits(lot)).isZero();
    }
    @Test
    void routeHistoryOrAssignedWorkCannotBeDetachedByReset() {
        int routeLot = takenLot();
        int volunteer = insertVolunteer("Volunteer");
        jdbc.update("INSERT INTO volunteer_routes (volunteer_id, points, status, lot_id, started_at, finished_at) "
            + "VALUES (?, '[]', 'finished', ?, NOW(), NOW())", volunteer, routeLot);
        int ticketLot = takenLot();
        int ticket = insertTicket(ticketLot, volunteer, "assigned");
        assertResetRejected(routeLot);
        assertResetRejected(ticketLot);
        assertThat(status("lots", routeLot)).isEqualTo("taken");
        assertThat(status("tickets", ticket)).isEqualTo("assigned");
        assertThat(resetAudits(routeLot)).isZero();
        assertThat(resetAudits(ticketLot)).isZero();
    }
    @Test
    void concurrentConfirmationWinsAndFailedResetHasNoSideEffects() throws Exception {
        int lot = takenLot();
        CountDownLatch confirmed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        Future<?> confirmation = executor.submit(() -> tx.executeWithoutResult(ignored -> {
            assertThat(jdbc.update("UPDATE lots SET status = 'confirmed' WHERE id = ? AND status = 'taken'", lot))
                .isEqualTo(1);
            confirmed.countDown();
            await(allowCommit);
        }));
        assertThat(confirmed.await(5, TimeUnit.SECONDS)).isTrue();
        Future<ApiException> reset = executor.submit(() -> {
            try {
                admin.resetLot(lot, ADMIN);
                return null;
            } catch (ApiException e) {
                return e;
            }
        });
        assertBlocked(reset);
        allowCommit.countDown();
        confirmation.get(5, TimeUnit.SECONDS);
        ApiException failure = reset.get(5, TimeUnit.SECONDS);
        assertThat(failure).isNotNull();
        assertThat(failure.getStatus()).isEqualTo(409);
        assertThat(status("lots", lot)).isEqualTo("confirmed");
        assertThat(lotClaim(lot)).containsEntry("taken_by", "Volunteer").containsKey("taken_at");
        assertThat(resetAudits(lot)).isZero();
    }
    private int takenLot() {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");
        jdbc.update("UPDATE lots SET status = 'taken', taken_at = NOW(), taken_by = 'Volunteer' WHERE id = ?", lot);
        return lot;
    }
    private int insertTicket(int lotId, int volunteerId, String ticketStatus) {
        int needy = insertNeedy("Recipient");
        return jdbc.queryForObject(
            "INSERT INTO tickets (needy_id, items, lot_id, quantity, status, created_at, assigned_volunteer_id) "
            + "VALUES (?, 'food', ?, 1, ?, NOW(), ?) RETURNING id",
            Integer.class, needy, lotId, ticketStatus, volunteerId);
    }
    private void assertResetRejected(int lotId) {
        assertThatThrownBy(() -> admin.resetLot(lotId, ADMIN))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus())
            .isEqualTo(409);
    }
    private Map<String, Object> lotClaim(int lotId) {
        return jdbc.queryForMap("SELECT taken_at, taken_by FROM lots WHERE id = ?", lotId);
    }
    private int resetAudits(int lotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'lot_reset' AND target_id = ?",
            Integer.class, lotId);
    }
    private static void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out coordinating concurrent lot reset");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating concurrent lot reset", e);
        }
    }
}
