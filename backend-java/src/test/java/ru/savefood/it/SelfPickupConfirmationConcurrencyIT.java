package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ru.savefood.background.MaintenanceTasks;
import ru.savefood.billing.BillingService;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.shop.LotUploadCleanup;
import ru.savefood.shop.ShopRepository;
import ru.savefood.shop.ShopService;
import ru.savefood.telegram.TelegramService;
import ru.savefood.upload.UploadService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PostgreSQL coverage for atomic self-pickup confirmation winner election. */
class SelfPickupConfirmationConcurrencyIT extends PostgresIT {

    private NeedyService needyService;
    private ShopService shopService;
    private TelegramService telegram;
    private MaintenanceTasks maintenance;
    private ExecutorService executor;

    @BeforeEach
    void wire() {
        needyService = new NeedyService(jdbc, new NeedyRepository(jdbc), new PasswordService());
        shopService = shopService(needyService);
        telegram = mock(TelegramService.class);
        maintenance = new MaintenanceTasks(jdbc, txManager, new RouteRevertService(jdbc), null,
            telegram, "embedded", "", "/tmp/savefood-self-pickup-it", 1, 0);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void twoConcurrentConfirmationsProduceExactlyOneWinner() throws Exception {
        Reservation reservation = selfPickupReservation();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Boolean> first = executor.submit(() -> confirmTogether(reservation, ready, start));
        Future<Boolean> second = executor.submit(() -> confirmTogether(reservation, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successes = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
            + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
        assertThat(successes).isEqualTo(1);
        assertFulfilledOnce(reservation);
    }

    @Test
    void confirmationThatWinsBeforeCancellationCannotBeUndoneOrRestocked() throws Exception {
        Reservation reservation = selfPickupReservation();
        CountDownLatch confirmed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        NeedyService sideEffect = mock(NeedyService.class);
        doAnswer(invocation -> {
            confirmed.countDown();
            await(allowCommit);
            return null;
        }).when(sideEffect).setProfileLastReceived(anyInt(), any(OffsetDateTime.class));
        ShopService gatedConfirmation = shopService(sideEffect);

        Future<Boolean> confirmation = executor.submit(() -> attemptConfirmation(gatedConfirmation, reservation));
        assertThat(confirmed.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> cancellation = executor.submit(() -> attemptCancellation(reservation));
        assertBlocked(cancellation);
        allowCommit.countDown();

        assertThat(confirmation.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(cancellation.get(5, TimeUnit.SECONDS)).isFalse();
        assertFulfilledOnce(reservation);
        verify(sideEffect, times(1)).setProfileLastReceived(
            org.mockito.ArgumentMatchers.eq(reservation.needyId()), any(OffsetDateTime.class));
    }

    @Test
    void cancellationThatWinsPreventsConfirmationAndRestocksOnce() throws Exception {
        Reservation reservation = selfPickupReservation();
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        Future<?> cancellation = executor.submit(() -> tx.executeWithoutResult(ignored -> {
            needyService.cancelTicket(reservation.needyId(), reservation.ticketId());
            cancelled.countDown();
            await(allowCommit);
        }));
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> confirmation = executor.submit(() -> attemptConfirmation(shopService, reservation));
        assertBlocked(confirmation);
        allowCommit.countDown();

        cancellation.get(5, TimeUnit.SECONDS);
        assertThat(confirmation.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(status("tickets", reservation.ticketId())).isEqualTo("cancelled");
        assertThat(lotQuantity(reservation.lotId())).isEqualTo(5.0);
        assertThat(confirmationNotificationCount(reservation.needyId())).isZero();
        assertThat(lastReceived(reservation.needyId())).isNull();
    }

    @Test
    void confirmationThatWinsBeforeExpiryCannotBeUndoneByTtl() throws Exception {
        Reservation reservation = selfPickupReservation();
        jdbc.update("UPDATE tickets SET expires_at = clock_timestamp() + INTERVAL '2 seconds' "
            + "WHERE id = ?", reservation.ticketId());
        CountDownLatch confirmed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        NeedyService sideEffect = mock(NeedyService.class);
        doAnswer(invocation -> {
            confirmed.countDown();
            await(allowCommit);
            return null;
        }).when(sideEffect).setProfileLastReceived(anyInt(), any(OffsetDateTime.class));

        Future<Boolean> confirmation = executor.submit(
            () -> attemptConfirmation(shopService(sideEffect), reservation));
        assertThat(confirmed.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(2_200);
        Future<?> ttl = executor.submit(maintenance::reservationTtlTick);
        // Expiry skips the lot held by the winning confirmation transaction.
        ttl.get(5, TimeUnit.SECONDS);
        allowCommit.countDown();

        assertThat(confirmation.get(5, TimeUnit.SECONDS)).isTrue();
        assertFulfilledOnce(reservation);
        assertThat(expirationNotificationCount(reservation.needyId())).isZero();
    }

    @Test
    void expiryThatWinsMakesConfirmationFailWhileTelegramIsStillPending() throws Exception {
        Reservation reservation = selfPickupReservation();
        expire(reservation);
        CountDownLatch ttlWon = new CountDownLatch(1);
        CountDownLatch releaseTelegram = new CountDownLatch(1);
        doAnswer(invocation -> {
            ttlWon.countDown();
            await(releaseTelegram);
            return null;
        }).when(telegram).notifyNeedy(anyInt(), anyString());

        Future<?> ttl = executor.submit(maintenance::reservationTtlTick);
        assertThat(ttlWon.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> confirmation = executor.submit(() -> attemptConfirmation(shopService, reservation));
        assertThat(confirmation.get(5, TimeUnit.SECONDS)).isFalse();
        releaseTelegram.countDown();

        ttl.get(5, TimeUnit.SECONDS);
        assertThat(status("tickets", reservation.ticketId())).isEqualTo("cancelled");
        assertThat(lotQuantity(reservation.lotId())).isEqualTo(5.0);
        assertThat(confirmationNotificationCount(reservation.needyId())).isZero();
        assertThat(expirationNotificationCount(reservation.needyId())).isEqualTo(1);
    }

    @Test
    void alreadyExpiredTicketIsRejectedBeforeTtlRuns() {
        Reservation reservation = selfPickupReservation();
        expire(reservation);

        assertThatThrownBy(() -> tx.execute(ignored -> shopService.confirmSelfPickup(
            reservation.shopId(), reservation.ticketId(), reservation.secret())))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Срок брони истёк");
        assertThat(status("tickets", reservation.ticketId())).isEqualTo("open");
        assertThat(lotQuantity(reservation.lotId())).isEqualTo(4.0);
        assertThat(confirmationNotificationCount(reservation.needyId())).isZero();
        assertThat(lastReceived(reservation.needyId())).isNull();
    }

    @Test
    void duplicateConfirmationProducesNoDuplicateSideEffects() {
        Reservation reservation = selfPickupReservation();
        tx.execute(ignored -> shopService.confirmSelfPickup(
            reservation.shopId(), reservation.ticketId(), reservation.secret()));
        OffsetDateTime firstReceived = lastReceived(reservation.needyId());

        assertThatThrownBy(() -> tx.execute(ignored -> shopService.confirmSelfPickup(
            reservation.shopId(), reservation.ticketId(), reservation.secret())))
            .isInstanceOf(ApiException.class);

        assertFulfilledOnce(reservation);
        assertThat(lastReceived(reservation.needyId())).isEqualTo(firstReceived);
    }

    @Test
    void normalSelfPickupStillRequiresTheQrSecretAndFulfilsTheReservation() {
        Reservation reservation = selfPickupReservation();

        assertThatThrownBy(() -> tx.execute(ignored -> shopService.confirmSelfPickup(
            reservation.shopId(), reservation.ticketId(), "wrong-secret")))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Код не совпадает");
        assertThat(status("tickets", reservation.ticketId())).isEqualTo("open");

        int confirmed = tx.execute(ignored -> shopService.confirmSelfPickup(
            reservation.shopId(), reservation.ticketId(), reservation.secret()));

        assertThat(confirmed).isEqualTo(reservation.ticketId());
        assertFulfilledOnce(reservation);
        assertThat(lastReceived(reservation.needyId())).isNotNull();
    }

    private Reservation selfPickupReservation() {
        int shopId = insertShop("Магазин", 43.238, 76.889);
        int lotId = insertLot(shopId, 5.0, "Выпечка");
        int needyId = insertNeedy("Получатель");
        int ticketId = needyService.createTicket(needyId, "хлеб", null, null, null,
            null, lotId, null, null, null, true);
        String secret = jdbc.queryForObject(
            "SELECT qr_secret FROM tickets WHERE id = ?", String.class, ticketId);
        return new Reservation(shopId, lotId, ticketId, needyId, secret);
    }

    private ShopService shopService(NeedyService sideEffect) {
        return new ShopService(jdbc, new ShopRepository(jdbc), mock(BillingService.class),
            sideEffect, mock(PasswordService.class), mock(UploadService.class),
            mock(LotUploadCleanup.class));
    }

    private boolean confirmTogether(Reservation reservation, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        return attemptConfirmation(shopService, reservation);
    }

    private boolean attemptConfirmation(ShopService service, Reservation reservation) {
        try {
            tx.execute(ignored -> service.confirmSelfPickup(
                reservation.shopId(), reservation.ticketId(), reservation.secret()));
            return true;
        } catch (ApiException expected) {
            return false;
        }
    }

    private boolean attemptCancellation(Reservation reservation) {
        try {
            tx.execute(ignored -> needyService.cancelTicket(
                reservation.needyId(), reservation.ticketId()));
            return true;
        } catch (ApiException expected) {
            return false;
        }
    }

    private void assertFulfilledOnce(Reservation reservation) {
        assertThat(status("tickets", reservation.ticketId())).isEqualTo("fulfilled");
        assertThat(jdbc.queryForObject("SELECT fulfilled_at IS NOT NULL FROM tickets WHERE id = ?",
            Boolean.class, reservation.ticketId())).isTrue();
        assertThat(lotQuantity(reservation.lotId())).isEqualTo(4.0);
        assertThat(confirmationNotificationCount(reservation.needyId())).isEqualTo(1);
    }

    private int confirmationNotificationCount(int needyId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE needy_id = ? AND type = 'self_pickup_confirmed'",
            Integer.class, needyId);
    }

    private int expirationNotificationCount(int needyId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE needy_id = ? AND type = 'self_pickup_expired'",
            Integer.class, needyId);
    }

    private OffsetDateTime lastReceived(int needyId) {
        return jdbc.queryForObject(
            "SELECT last_received_at FROM needy_profile WHERE needy_id = ?",
            OffsetDateTime.class, needyId);
    }

    private void expire(Reservation reservation) {
        jdbc.update("UPDATE tickets SET expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE id = ?",
            reservation.ticketId());
    }

    private static void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out coordinating self-pickup race");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating self-pickup race", e);
        }
    }

    private record Reservation(int shopId, int lotId, int ticketId, int needyId, String secret) {
    }
}
