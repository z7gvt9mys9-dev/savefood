package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import ru.savefood.shop.ShopRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Focused regression coverage for PATCH /lots/{id} row races. */
class LotPatchConcurrencyIT extends PostgresIT {

    private ShopRepository repo;
    private ExecutorService executor;

    @BeforeEach
    void wire() {
        repo = new ShopRepository(jdbc);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void descriptionOnlyPatchPreservesAConcurrentReservation() throws Exception {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");

        Map<String, Object> updated = patchWhileRowMutationCommits(lot,
            () -> jdbc.update("UPDATE lots SET quantity = quantity - 1 WHERE id = ?", lot),
            () -> patch(lot, "updated", null));

        assertThat(updated).isNotNull();
        assertThat(updated.get("description")).isEqualTo("updated");
        assertThat(updated.get("quantity")).isEqualTo(4.0);
        assertThat(updated.get("initial_quantity")).isEqualTo(5.0);
    }

    @Test
    void descriptionOnlyPatchLosesToAConcurrentClaim() throws Exception {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");

        Map<String, Object> updated = patchWhileRowMutationCommits(lot,
            () -> jdbc.update("UPDATE lots SET status = 'taken', taken_at = NOW() WHERE id = ?", lot),
            () -> patch(lot, "updated", null));

        assertThat(updated).isNull();
        assertThat(repo.getLotById(lot))
            .containsEntry("description", "лот")
            .containsEntry("status", "taken");
    }

    @Test
    void explicitQuantityPatchPreservesAConcurrentReservation() throws Exception {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");

        Map<String, Object> updated = patchWhileRowMutationCommits(lot,
            () -> jdbc.update("UPDATE lots SET quantity = quantity - 1 WHERE id = ?", lot),
            () -> patch(lot, null, 8.0));

        assertThat(updated).isNotNull();
        assertThat(updated.get("quantity")).isEqualTo(7.0);
        assertThat(updated.get("initial_quantity")).isEqualTo(8.0);
    }

    @Test
    void stalePatchCannotModifyAnAlreadyTakenLot() {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");
        Map<String, Object> staleSnapshot = repo.getLotById(lot);
        jdbc.update("UPDATE lots SET status = 'taken', taken_at = NOW() WHERE id = ?", lot);

        Map<String, Object> updated = repo.updateLot(lot, "updated", 9.0,
            LocalDate.now().plusDays(20), "new address", "Vegetables", "new comment",
            true, "pcs", 0.5, number(staleSnapshot, "quantity"),
            number(staleSnapshot, "initial_quantity"));

        assertThat(staleSnapshot).containsEntry("status", "active");
        assertThat(updated).isNull();
        assertThat(repo.getLotById(lot))
            .containsEntry("description", "лот")
            .containsEntry("status", "taken");
        assertThat(lotQuantity(lot)).isEqualTo(5.0);
    }

    @Test
    void validQuantityEditUpdatesInitialQuantityConsistently() {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");
        jdbc.update("UPDATE lots SET quantity = quantity - 1 WHERE id = ?", lot);

        Map<String, Object> updated = patch(lot, null, 7.0);

        assertThat(updated)
            .containsEntry("quantity", 7.0)
            .containsEntry("initial_quantity", 8.0);
    }

    @Test
    void normalPatchUpdatesRequestedFieldsAndPreservesTheRest() {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");
        jdbc.update("UPDATE lots SET photo = '/uploads/original.jpg', time_slot = '10:00-12:00' "
            + "WHERE id = ?", lot);
        LocalDate expiry = LocalDate.now().plusDays(20);

        Map<String, Object> updated = repo.updateLot(lot, "fresh description", null,
            expiry, "new address", "Vegetables", "new comment", true, "pcs", 0.5,
            5.0, 5.0);

        assertThat(updated)
            .containsEntry("description", "fresh description")
            .containsEntry("quantity", 5.0)
            .containsEntry("initial_quantity", 5.0)
            .containsEntry("expiry_date", expiry)
            .containsEntry("address", "new address")
            .containsEntry("category", "Vegetables")
            .containsEntry("comment", "new comment")
            .containsEntry("requires_cold", true)
            .containsEntry("unit", "pcs")
            .containsEntry("unit_weight_kg", 0.5)
            .containsEntry("photo", "/uploads/original.jpg")
            .containsEntry("time_slot", "10:00-12:00")
            .containsEntry("status", "active");
    }

    private Map<String, Object> patch(int lotId, String description, Double quantity) {
        Map<String, Object> snapshot = repo.getLotById(lotId);
        return repo.updateLot(lotId, description, quantity, null, null, null, null,
            null, null, null, number(snapshot, "quantity"), number(snapshot, "initial_quantity"));
    }

    private static Double number(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /**
     * Hold an uncommitted reservation/claim row lock while PATCH starts. The old
     * read-then-write implementation completed its SELECT before blocking on its
     * UPDATE, which deterministically gave it the stale snapshot under test.
     */
    private Map<String, Object> patchWhileRowMutationCommits(
            int lotId, Runnable rowMutation, Supplier<Map<String, Object>> patch) throws Exception {
        assertThat(repo.getLotById(lotId)).containsEntry("status", "active");
        CountDownLatch mutationApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        Future<?> mutation = executor.submit(() -> tx.executeWithoutResult(ignored -> {
            rowMutation.run();
            mutationApplied.countDown();
            await(allowCommit);
        }));
        assertThat(mutationApplied.await(5, TimeUnit.SECONDS)).isTrue();

        Future<Map<String, Object>> patchResult = executor.submit(patch::get);
        assertThatThrownBy(() -> patchResult.get(500, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);

        allowCommit.countDown();
        mutation.get(5, TimeUnit.SECONDS);
        return patchResult.get(5, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release concurrent lot mutation");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating lot race", e);
        }
    }
}
