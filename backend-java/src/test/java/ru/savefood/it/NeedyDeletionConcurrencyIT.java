package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
