package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ru.savefood.background.MaintenanceTasks;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.volunteer.VolunteerService;
import ru.savefood.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PostgreSQL regression coverage for reservation TTL winner election. */
class ReservationTtlConcurrencyIT extends PostgresIT {

    private MaintenanceTasks maintenance;
    private NeedyService needyService;
    private VolunteerService volunteerService;
    private TelegramService telegram;
    private ExecutorService executor;

    @BeforeEach
    void wire() {
        needyService = new NeedyService(jdbc, new NeedyRepository(jdbc), new PasswordService());
        RouteRevertService revert = new RouteRevertService(jdbc);
        telegram = mock(TelegramService.class);
        maintenance = new MaintenanceTasks(jdbc, txManager, revert, null, telegram,
            "embedded", "", "/tmp/savefood-reservation-ttl-it", 1, 0);
        volunteerService = new VolunteerService(jdbc, new VolunteerRepository(jdbc), revert,
            new PasswordService(), needyService, null, "Europe/Moscow");
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void assignmentThatWinsBeforeTtlRemainsAssignedAndInItsRoute() throws Exception {
        Reservation reservation = expiredReservation();
        int volunteerId = insertVolunteer("Волонтёр");
        CountDownLatch assigned = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        Future<VolunteerService.StartRouteResult> assignment = executor.submit(() -> tx.execute(ignored -> {
            VolunteerService.StartRouteResult result = volunteerService.startRoute(
                volunteerId, volunteer(volunteerId), reservation.lotId(), null);
            assigned.countDown();
            await(allowCommit);
            return result;
        }));
        assertThat(assigned.await(5, TimeUnit.SECONDS)).isTrue();

        Future<?> ttl = executor.submit(maintenance::reservationTtlTick);
        assertBlocked(ttl);
        allowCommit.countDown();

        VolunteerService.StartRouteResult route = assignment.get(5, TimeUnit.SECONDS);
        ttl.get(5, TimeUnit.SECONDS);
        assertThat(status("tickets", reservation.ticketId())).isEqualTo("assigned");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM volunteer_routes WHERE id = ? AND status = 'in_progress' "
            + "AND CAST(points AS jsonb) @> CAST(? AS jsonb)", Integer.class, route.routeId(),
            "[{\"kind\":\"ticket\",\"ticket_id\":" + reservation.ticketId() + "}]")).isEqualTo(1);
        assertThat(lotQuantity(reservation.lotId())).isEqualTo(4.0);
        assertThat(expirationNotificationCount(reservation.needyId())).isZero();
        verify(telegram, never()).notifyNeedy(anyInt(), anyString());
    }

    @Test
    void ttlThatWinsBeforeAssignmentCancelsAndPreventsTheRoute() throws Exception {
        Reservation reservation = expiredReservation();
        int volunteerId = insertVolunteer("Волонтёр");
        CountDownLatch ttlWon = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        doAnswer(invocation -> {
            ttlWon.countDown();
            await(allowCommit);
            return null;
        }).when(telegram).notifyNeedy(anyInt(), anyString());

        Future<?> ttl = executor.submit(maintenance::reservationTtlTick);
        assertThat(ttlWon.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> assignment = executor.submit(() -> {
            try {
                tx.execute(ignored -> volunteerService.startRoute(
                    volunteerId, volunteer(volunteerId), reservation.lotId(), null));
                return true;
            } catch (ApiException expected) {
                return false;
            }
        });
        assertBlocked(assignment);
        allowCommit.countDown();

        ttl.get(5, TimeUnit.SECONDS);
        assertThat(assignment.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(status("tickets", reservation.ticketId())).isEqualTo("cancelled");
        assertThat(lotQuantity(reservation.lotId())).isEqualTo(5.0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM volunteer_routes WHERE lot_id = ?", Integer.class,
            reservation.lotId())).isZero();
        assertThat(expirationNotificationCount(reservation.needyId())).isEqualTo(1);
        verify(telegram, times(1)).notifyNeedy(eq(reservation.needyId()),
            org.mockito.ArgumentMatchers.contains("истекла"));
    }

    @Test
    void twoTtlWorkersRestoreAndNotifyExactlyOnce() throws Exception {
        Reservation reservation = expiredReservation();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<?> first = executor.submit(() -> runTtlTogether(ready, start));
        Future<?> second = executor.submit(() -> runTtlTogether(ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        assertThat(status("tickets", reservation.ticketId())).isEqualTo("cancelled");
        assertThat(lotQuantity(reservation.lotId())).isEqualTo(5.0);
        assertThat(expirationNotificationCount(reservation.needyId())).isEqualTo(1);
        verify(telegram, times(1)).notifyNeedy(eq(reservation.needyId()),
            org.mockito.ArgumentMatchers.contains("истекла"));
    }

    @Test
    void ordinaryExpiredOpenTicketStillExpiresNormally() {
        Reservation reservation = expiredReservation();

        maintenance.reservationTtlTick();

        assertThat(status("tickets", reservation.ticketId())).isEqualTo("cancelled");
        assertThat(lotQuantity(reservation.lotId())).isEqualTo(5.0);
        assertThat(expirationNotificationCount(reservation.needyId())).isEqualTo(1);
        verify(telegram, times(1)).notifyNeedy(eq(reservation.needyId()),
            org.mockito.ArgumentMatchers.contains("истекла"));
    }

    private Reservation expiredReservation() {
        int lotId = insertLot(insertShop("Магазин", 43.238, 76.889), 5.0, "Выпечка");
        int needyId = insertNeedy("Получатель");
        int ticketId = needyService.createTicket(needyId, "хлеб", "адрес", 43.24, 76.90,
            null, lotId, null, null, null, false);
        jdbc.update("UPDATE tickets SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
            ticketId);
        return new Reservation(lotId, ticketId, needyId);
    }

    private Map<String, Object> volunteer(int volunteerId) {
        return Map.of(
            "id", volunteerId,
            "name", "Волонтёр",
            "lat", 43.238,
            "lon", 76.889,
            "has_thermal_bag", true,
            "capacity_kg", 20.0);
    }

    private int expirationNotificationCount(int needyId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE needy_id = ? AND type = 'reservation_expired'",
            Integer.class, needyId);
    }

    private void runTtlTogether(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        maintenance.reservationTtlTick();
    }

    private static void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out coordinating reservation TTL race");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating reservation TTL race", e);
        }
    }

    private record Reservation(int lotId, int ticketId, int needyId) {
    }
}
