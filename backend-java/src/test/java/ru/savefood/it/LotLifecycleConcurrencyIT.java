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
import java.util.concurrent.atomic.AtomicInteger;
import ru.savefood.background.MaintenanceTasks;
import ru.savefood.shop.ShopRepository;
import ru.savefood.volunteer.RouteRevertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Focused regression coverage for competing lot lifecycle transitions. */
class LotLifecycleConcurrencyIT extends PostgresIT {

    private ShopRepository lots;
    private RouteRevertService revert;
    private MaintenanceTasks maintenance;
    private ExecutorService executor;

    @BeforeEach
    void wire() {
        lots = new ShopRepository(jdbc);
        revert = new RouteRevertService(jdbc);
        maintenance = new MaintenanceTasks(jdbc, txManager, revert, null, null,
            "embedded", "", "/tmp/savefood-lifecycle-it", 1, 0);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void claimThatCommitsAfterExpiryScanCannotBeOverwritten() throws Exception {
        int lot = expiringLot();
        int ticket = insertOpenTicket(lot);

        raceClaimCommitAgainst(maintenance::expireTick, lot);

        assertThat(status("lots", lot)).isEqualTo("taken");
        assertThat(status("tickets", ticket)).isEqualTo("open");
        assertThat(notificationCount(lot, "lot_expired_soon")).isZero();
    }

    @Test
    void claimThatCommitsAfterDeleteValidationCannotBeOverwritten() throws Exception {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");
        int ticket = insertOpenTicket(lot);

        AtomicInteger deleteWins = new AtomicInteger();
        raceClaimCommitAgainst(() -> {
            if (lots.deleteLot(lot)) {
                deleteWins.incrementAndGet();
            }
        }, lot);

        assertThat(status("lots", lot)).isEqualTo("taken");
        assertThat(status("tickets", ticket)).isEqualTo("open");
        assertThat(deleteWins).hasValue(0);
        assertThat(notificationCount(lot, "lot_removed")).isZero();
    }

    @Test
    void routeRevertBeatsAStaleConfirmationWithoutConfirmationSideEffects() throws Exception {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");
        int ticket = insertOpenTicket(lot);
        int volunteer = insertVolunteer("Volunteer");
        jdbc.update("UPDATE lots SET status = 'taken', taken_at = NOW() WHERE id = ?", lot);
        jdbc.update("UPDATE tickets SET status = 'assigned', assigned_volunteer_id = ? WHERE id = ?",
            volunteer, ticket);
        Map<String, Object> staleConfirmationRead = lots.getLotById(lot);
        String points = """
            [{"kind":"shop","lat":43.238,"lon":76.889},
             {"kind":"ticket","ticket_id":%d,"lat":43.24,"lon":76.90}]
            """.formatted(ticket);
        CountDownLatch reverted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        Future<?> revertResult = executor.submit(() -> tx.executeWithoutResult(ignored -> {
            revert.revertRouteLot(lot, points);
            reverted.countDown();
            await(allowCommit);
        }));
        assertThat(reverted.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicInteger confirmationSideEffects = new AtomicInteger();
        Future<Boolean> confirmation = executor.submit(() -> {
            boolean won = lots.confirmLotTransfer(lot);
            if (won) {
                confirmationSideEffects.incrementAndGet();
            }
            return won;
        });
        assertBlocked(confirmation);
        allowCommit.countDown();

        revertResult.get(5, TimeUnit.SECONDS);
        assertThat(staleConfirmationRead).containsEntry("status", "taken");
        assertThat(confirmation.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(confirmationSideEffects).hasValue(0);
        assertThat(status("lots", lot)).isEqualTo("active");
        assertThat(status("tickets", ticket)).isEqualTo("open");
    }

    @Test
    void twoConcurrentConfirmationsHaveOneWinnerAndOneSetOfSideEffects() throws Exception {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");
        jdbc.update("UPDATE lots SET status = 'taken', taken_at = NOW() WHERE id = ?", lot);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger transitionWins = new AtomicInteger();
        AtomicInteger sideEffects = new AtomicInteger();

        Future<?> first = executor.submit(() -> confirmTogether(lot, ready, start, transitionWins, sideEffects));
        Future<?> second = executor.submit(() -> confirmTogether(lot, ready, start, transitionWins, sideEffects));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        assertThat(status("lots", lot)).isEqualTo("confirmed");
        assertThat(transitionWins).hasValue(1);
        assertThat(sideEffects).hasValue(1);
    }

    @Test
    void staleLifecycleAttemptsLoseWithoutSideEffects() {
        int confirmed = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");
        jdbc.update("UPDATE lots SET status = 'confirmed' WHERE id = ?", confirmed);

        assertThat(lots.confirmLotTransfer(confirmed)).isFalse();
        assertThat(lots.deleteLot(confirmed)).isFalse();
        assertThat(status("lots", confirmed)).isEqualTo("confirmed");
        assertThat(notificationCount(confirmed, "lot_removed")).isZero();
    }

    @Test
    void normalExpiryDeleteConfirmationAndRevertTransitionsStillWork() {
        int shop = insertShop("Shop", 43.238, 76.889);

        int expiring = insertLot(shop, 5.0, "Bakery");
        jdbc.update("UPDATE lots SET expiry_date = CURRENT_DATE + 1 WHERE id = ?", expiring);
        int expiryTicket = insertOpenTicket(expiring);
        maintenance.expireTick();
        assertThat(status("lots", expiring)).isEqualTo("expired");
        assertThat(status("tickets", expiryTicket)).isEqualTo("cancelled");
        assertThat(notificationCount(expiring, "lot_expired_soon")).isEqualTo(1);

        int removed = insertLot(shop, 5.0, "Bakery");
        int removedTicket = insertOpenTicket(removed);
        assertThat(lots.deleteLot(removed)).isTrue();
        assertThat(status("lots", removed)).isEqualTo("removed");
        assertThat(status("tickets", removedTicket)).isEqualTo("cancelled");
        assertThat(notificationCount(removed, "lot_removed")).isEqualTo(1);

        int confirmed = insertLot(shop, 5.0, "Bakery");
        jdbc.update("UPDATE lots SET status = 'taken', taken_at = NOW() WHERE id = ?", confirmed);
        assertThat(lots.confirmLotTransfer(confirmed)).isTrue();
        assertThat(status("lots", confirmed)).isEqualTo("confirmed");

        int returned = insertLot(shop, 5.0, "Bakery");
        jdbc.update("UPDATE lots SET status = 'taken', taken_at = NOW() WHERE id = ?", returned);
        revert.revertRouteLot(returned, "[{\"kind\":\"shop\",\"done\":false}]");
        assertThat(status("lots", returned)).isEqualTo("active");
    }

    private int expiringLot() {
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 5.0, "Bakery");
        jdbc.update("UPDATE lots SET expiry_date = CURRENT_DATE + 1 WHERE id = ?", lot);
        return lot;
    }

    private int insertOpenTicket(int lotId) {
        int needy = insertNeedy("Recipient");
        return jdbc.queryForObject(
            "INSERT INTO tickets (needy_id, items, lot_id, quantity, status, created_at) "
            + "VALUES (?, 'food', ?, 1, 'open', NOW()) RETURNING id",
            Integer.class, needy, lotId);
    }

    private int notificationCount(int lotId, String type) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE lot_id = ? AND type = ?", Integer.class,
            lotId, type);
    }

    private void raceClaimCommitAgainst(Runnable competingTransition, int lotId) throws Exception {
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        Future<?> claim = executor.submit(() -> tx.executeWithoutResult(ignored -> {
            int rows = jdbc.update(
                "UPDATE lots SET status = 'taken', taken_at = NOW(), taken_by = 'Volunteer' "
                + "WHERE id = ? AND status = 'active'", lotId);
            assertThat(rows).isEqualTo(1);
            claimed.countDown();
            await(allowCommit);
        }));
        assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();

        Future<?> transition = executor.submit(competingTransition);
        assertBlocked(transition);
        allowCommit.countDown();

        claim.get(5, TimeUnit.SECONDS);
        transition.get(5, TimeUnit.SECONDS);
    }

    private void confirmTogether(int lotId, CountDownLatch ready, CountDownLatch start,
                                 AtomicInteger wins, AtomicInteger sideEffects) {
        ready.countDown();
        await(start);
        if (lots.confirmLotTransfer(lotId)) {
            wins.incrementAndGet();
            sideEffects.incrementAndGet();
        }
    }

    private static void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out coordinating lot lifecycle race");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating lot lifecycle race", e);
        }
    }
}
