package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
/** PostgreSQL coverage for the recipient-erasure persistence barrier. */
class NeedyDeletionConcurrencyIT extends PostgresIT {
    private NeedyRepository repo;
    private NeedyService service;
    private ExecutorService executor;
    @BeforeEach
    void wire() {
        repo = new NeedyRepository(jdbc);
        service = new NeedyService(jdbc, repo, new PasswordService());
        executor = Executors.newFixedThreadPool(2);
    }
    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }
    @Test
    void deleteWinsAgainstAlreadyAuthenticatedTicketCreation() throws Exception {
        int lotId = insertLot(insertShop("Shop", 43.238, 76.889), 3.0, "Bakery");
        int needyId = insertNeedy("Recipient");
        assertThat(repo.getNeedyById(needyId)).containsEntry("status", "active");
        CountDownLatch erased = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        Future<?> deletion = executor.submit(() -> tx.executeWithoutResult(ignored -> {
            service.eraseAccount(needyId);
            erased.countDown();
            await(allowDeleteCommit);
        }));
        assertThat(erased.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Integer> mutation = executor.submit(() -> tx.execute(ignored ->
            service.createTicket(needyId, "private items", "Private street 7", 43.24, 76.90,
                null, lotId, "7", "3", "2", false)));
        assertBlocked(mutation);
        allowDeleteCommit.countDown();
        deletion.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> mutation.get(5, TimeUnit.SECONDS))
            .hasRootCauseInstanceOf(ApiException.class)
            .rootCause().extracting(cause -> ((ApiException) cause).getStatus()).isEqualTo(403);
        assertThat(lotQuantity(lotId)).isEqualTo(3.0);
        assertThat(count("tickets", "needy_id", needyId)).isZero();
        assertThat(count("notifications", "needy_id", needyId)).isZero();
        assertErased(needyId);
    }
    @Test
    void ticketCreationWinsThenDeletionErasesItAndRestoresInventory() throws Exception {
        int lotId = insertLot(insertShop("Shop", 43.238, 76.889), 3.0, "Bakery");
        int needyId = insertNeedy("Recipient");
        CountDownLatch ticketInserted = new CountDownLatch(1);
        CountDownLatch allowTicketCommit = new CountDownLatch(1);
        Future<Integer> mutation = executor.submit(() -> tx.execute(ignored -> {
            int ticketId = service.createTicket(needyId, "private items", "Private street 7",
                43.24, 76.90, null, lotId, "7", "3", "2", false);
            ticketInserted.countDown();
            await(allowTicketCommit);
            return ticketId;
        }));
        assertThat(ticketInserted.await(5, TimeUnit.SECONDS)).isTrue();
        Future<?> deletion = executor.submit(() -> tx.executeWithoutResult(
            ignored -> service.eraseAccount(needyId)));
        assertBlocked(deletion);
        allowTicketCommit.countDown();
        int ticketId = mutation.get(5, TimeUnit.SECONDS);
        deletion.get(5, TimeUnit.SECONDS);
        assertThat(lotQuantity(lotId)).isEqualTo(3.0);
        assertThat(jdbc.queryForMap(
            "SELECT status, items, address, lat, lon, apartment, floor_num, entrance "
                + "FROM tickets WHERE id = ?", ticketId))
            .containsEntry("status", "cancelled")
            .containsEntry("items", null)
            .containsEntry("address", null)
            .containsEntry("lat", null)
            .containsEntry("lon", null)
            .containsEntry("apartment", null)
            .containsEntry("floor_num", null)
            .containsEntry("entrance", null);
        assertErased(needyId);
    }
    @Test
    void concurrentErasuresRestoreAndNotifyOnceAndRetryIsIdempotent() throws Exception {
        int lotId = insertLot(insertShop("Shop", 43.238, 76.889), 3.0, "Bakery");
        int needyId = insertNeedy("Recipient");
        int volunteerId = insertVolunteer("Volunteer");
        int ticketId = tx.execute(ignored -> service.createTicket(needyId, "private items",
            "Private street 7", 43.24, 76.90, null, lotId, "7", "3", "2", false));
        jdbc.update(
            "UPDATE tickets SET status = 'assigned', assigned_volunteer = 'Volunteer', "
                + "assigned_volunteer_id = ?, delivery_photo = '/private/one.jpg' WHERE id = ?",
            volunteerId, ticketId);
        installTicketTransitionAudit();
        List<NeedyService.EraseResult> results = eraseTwiceWhileFirstTransactionIsOpen(needyId);
        assertThat(lotQuantity(lotId)).isEqualTo(3.0);
        assertThat(status("tickets", ticketId)).isEqualTo("cancelled");
        assertThat(transitionCount(ticketId)).isEqualTo(1);
        assertThat(cancellationNotificationCount(volunteerId)).isEqualTo(1);
        assertThat(results.stream().flatMap(result -> result.photos().stream()))
            .containsExactly("/private/one.jpg");
        assertErased(needyId);
        NeedyService.EraseResult retry = tx.execute(ignored -> service.eraseAccount(needyId));
        assertThat(retry.photos()).isEmpty();
        assertThat(lotQuantity(lotId)).isEqualTo(3.0);
        assertThat(transitionCount(ticketId)).isEqualTo(1);
        assertThat(cancellationNotificationCount(volunteerId)).isEqualTo(1);
    }
    @Test
    void concurrentErasuresRestoreEveryEligibleReservationExactlyOnce() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        int firstLotId = insertLot(shopId, 3.0, "Bakery");
        int secondLotId = insertLot(shopId, 4.0, "Produce");
        int needyId = insertNeedy("Recipient");
        int volunteerId = insertVolunteer("Volunteer");
        jdbc.execute("DROP INDEX uq_tickets_one_active_per_needy");
        int firstTicketId = tx.execute(ignored -> service.createTicket(needyId, "bread", "Address",
            43.24, 76.90, null, firstLotId, null, null, null, false));
        int secondTicketId = tx.execute(ignored -> {
            jdbc.update("UPDATE lots SET quantity = quantity - 1 WHERE id = ?", secondLotId);
            return jdbc.queryForObject(
                "INSERT INTO tickets (needy_id, items, address, lat, lon, lot_id, quantity, "
                    + "status, created_at) VALUES (?, 'fruit', 'Address', 43.24, 76.90, ?, 1, "
                    + "'open', NOW()) RETURNING id",
                Integer.class, needyId, secondLotId);
        });
        jdbc.update(
            "UPDATE tickets SET status = 'assigned', assigned_volunteer = 'Volunteer', "
                + "assigned_volunteer_id = ? WHERE id IN (?, ?)",
            volunteerId, firstTicketId, secondTicketId);
        installTicketTransitionAudit();
        eraseTwiceWhileFirstTransactionIsOpen(needyId);
        assertThat(lotQuantity(firstLotId)).isEqualTo(3.0);
        assertThat(lotQuantity(secondLotId)).isEqualTo(4.0);
        assertThat(status("tickets", firstTicketId)).isEqualTo("cancelled");
        assertThat(status("tickets", secondTicketId)).isEqualTo("cancelled");
        assertThat(transitionCount(firstTicketId)).isEqualTo(1);
        assertThat(transitionCount(secondTicketId)).isEqualTo(1);
        assertThat(cancellationNotificationCount(volunteerId)).isEqualTo(2);
        assertErased(needyId);
    }
    @Test
    void deleteWinsAgainstInFlightProfileAddressMutation() throws Exception {
        int needyId = insertNeedy("Recipient");
        CountDownLatch erased = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        Future<?> deletion = executor.submit(() -> tx.executeWithoutResult(ignored -> {
            service.eraseAccount(needyId);
            erased.countDown();
            await(allowDeleteCommit);
        }));
        assertThat(erased.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Map<String, Object>> mutation = executor.submit(() -> tx.execute(ignored ->
            service.createOrUpdateProfile(needyId, "New private address", 4, "private prefs",
                "urgent", "evening", "9", "4", "1", "Almaty", 43.25, 76.91, false)));
        assertBlocked(mutation);
        allowDeleteCommit.countDown();
        deletion.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> mutation.get(5, TimeUnit.SECONDS))
            .hasRootCauseInstanceOf(ApiException.class);
        assertErased(needyId);
    }
    @Test
    void activeRecipientMutationsStillWorkNormally() {
        int lotId = insertLot(insertShop("Shop", 43.238, 76.889), 3.0, "Bakery");
        int needyId = insertNeedy("Recipient");
        Map<String, Object> profile = tx.execute(ignored -> service.createOrUpdateProfile(
            needyId, "Active address", 3, null, null, null, "5", null, null,
            "Almaty", 43.25, 76.91, false));
        int ticketId = tx.execute(ignored -> service.createTicket(needyId, "bread", "Active address",
            43.25, 76.91, null, lotId, "5", null, null, false));
        assertThat(profile).containsEntry("address", "Active address");
        assertThat(status("tickets", ticketId)).isEqualTo("open");
        assertThat(lotQuantity(lotId)).isEqualTo(2.0);
    }
    private int count(String table, String ownerColumn, int ownerId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE " + ownerColumn + " = ?",
            Integer.class, ownerId);
    }
    private List<NeedyService.EraseResult> eraseTwiceWhileFirstTransactionIsOpen(int needyId)
            throws Exception {
        CountDownLatch firstErased = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        Future<NeedyService.EraseResult> first = executor.submit(() -> tx.execute(ignored -> {
            NeedyService.EraseResult result = service.eraseAccount(needyId);
            firstErased.countDown();
            await(allowFirstCommit);
            return result;
        }));
        assertThat(firstErased.await(5, TimeUnit.SECONDS)).isTrue();
        Future<NeedyService.EraseResult> second = executor.submit(() ->
            tx.execute(ignored -> service.eraseAccount(needyId)));
        assertBlocked(second);
        allowFirstCommit.countDown();
        return List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
    }
    private void installTicketTransitionAudit() {
        jdbc.execute("CREATE TABLE ticket_status_transitions (ticket_id integer NOT NULL, "
            + "old_status text NOT NULL, new_status text NOT NULL)");
        jdbc.execute("CREATE FUNCTION audit_ticket_status_transition() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "INSERT INTO ticket_status_transitions (ticket_id, old_status, new_status) "
            + "VALUES (NEW.id, OLD.status, NEW.status); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER audit_ticket_status_transition "
            + "AFTER UPDATE OF status ON tickets FOR EACH ROW "
            + "WHEN (OLD.status IS DISTINCT FROM NEW.status) "
            + "EXECUTE FUNCTION audit_ticket_status_transition()");
    }
    private int transitionCount(int ticketId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM ticket_status_transitions WHERE ticket_id = ? "
                + "AND old_status IN ('open','assigned') AND new_status = 'cancelled'",
            Integer.class, ticketId);
    }
    private int cancellationNotificationCount(int volunteerId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE volunteer_id = ? AND type = 'ticket_cancelled'",
            Integer.class, volunteerId);
    }
    private void assertErased(int needyId) {
        assertThat(jdbc.queryForMap("SELECT name, contact, status FROM needy WHERE id = ?", needyId))
            .containsEntry("name", "Удалённый аккаунт")
            .containsEntry("contact", null)
            .containsEntry("status", "deleted");
        assertThat(count("needy_profile", "needy_id", needyId)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM tickets WHERE needy_id = ? AND "
                + "(items IS NOT NULL OR address IS NOT NULL OR lat IS NOT NULL OR lon IS NOT NULL "
                + "OR apartment IS NOT NULL OR floor_num IS NOT NULL OR entrance IS NOT NULL)",
            Integer.class, needyId)).isZero();
    }
    private static void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out coordinating recipient deletion race");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating recipient deletion race", e);
        }
    }
}
