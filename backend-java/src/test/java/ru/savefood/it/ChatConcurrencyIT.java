package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import ru.savefood.chat.ChatController;
import ru.savefood.chat.ChatService;
import ru.savefood.chat.dto.MessageIn;
import ru.savefood.push.PushDispatchService;
import ru.savefood.security.CurrentUser;
import ru.savefood.telegram.TelegramService;
import ru.savefood.web.ApiException;
import ru.savefood.web.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PostgreSQL row-lock regression coverage for chat send versus ticket closure. */
class ChatConcurrencyIT extends PostgresIT {

    private ChatController controller;
    private TelegramService telegram;
    private PushDispatchService push;
    private ExecutorService executor;

    @BeforeEach
    void wire() {
        ChatService chat = new ChatService(jdbc, txManager);
        telegram = mock(TelegramService.class);
        push = mock(PushDispatchService.class);
        controller = new ChatController(chat, new RateLimiter(), telegram, push);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void messageThatWinsBeforeCancellationPersists() throws Exception {
        Fixture fixture = assignedTicket();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch allowMessageCommit = new CountDownLatch(1);

        Future<Map<String, Object>> message = executor.submit(() -> tx.execute(ignored -> {
            Map<String, Object> result = post(fixture.ticketId(), fixture.needyUser(), "до отмены");
            inserted.countDown();
            await(allowMessageCommit);
            return result;
        }));
        assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch cancellationStarted = new CountDownLatch(1);
        Future<?> cancellation = executor.submit(() -> {
            cancellationStarted.countDown();
            jdbc.update("UPDATE tickets SET status = 'cancelled', assigned_volunteer_id = NULL WHERE id = ?",
                fixture.ticketId());
        });
        assertThat(cancellationStarted.await(5, TimeUnit.SECONDS)).isTrue();
        allowMessageCommit.countDown();

        assertThat(message.get(5, TimeUnit.SECONDS)).containsEntry("body", "до отмены");
        cancellation.get(5, TimeUnit.SECONDS);
        assertThat(messageCount(fixture.ticketId())).isOne();
        assertThat(status("tickets", fixture.ticketId())).isEqualTo("cancelled");
    }

    @Test
    void cancellationThatWinsRejectsMessageWithoutNotifications() throws Exception {
        assertClosureWins("cancelled", true);
    }

    @Test
    void fulfillmentThatWinsRejectsMessageWithoutNotifications() throws Exception {
        assertClosureWins("fulfilled", false);
    }

    @Test
    void volunteerReassignmentThatWinsRejectsStaleSender() throws Exception {
        Fixture fixture = assignedTicket();
        int replacement = insertVolunteer("Новый волонтёр");
        ApiException failure = assignmentChangeWins(fixture,
            "UPDATE tickets SET assigned_volunteer_id = ? WHERE id = ?", replacement);

        assertThat(failure.getStatus()).isEqualTo(403);
        assertThat(messageCount(fixture.ticketId())).isZero();
        verifyNoMessageNotifications();
    }

    @Test
    void volunteerRemovalThatWinsRejectsStaleSender() throws Exception {
        Fixture fixture = assignedTicket();
        ApiException failure = assignmentChangeWins(fixture,
            "UPDATE tickets SET assigned_volunteer_id = NULL WHERE id = ?");

        assertThat(failure.getStatus()).isEqualTo(403);
        assertThat(messageCount(fixture.ticketId())).isZero();
        verifyNoMessageNotifications();
    }

    @Test
    void activeAssignedChatStillPersistsAndNotifiesCounterpart() {
        Fixture fixture = assignedTicket();

        Map<String, Object> message = post(fixture.ticketId(), fixture.needyUser(), "активный чат");

        assertThat(message).containsEntry("sender_role", "needy")
            .containsEntry("sender_id", fixture.needyId())
            .containsEntry("body", "активный чат");
        assertThat(messageCount(fixture.ticketId())).isOne();
        verify(telegram).notifyVolunteer(fixture.volunteerId(), "◇ Получатель: активный чат");
        verify(push).notifyRole("volunteer", fixture.volunteerId(),
            "Сообщение от получателя: активный чат", "/volunteer");
    }

    @Test
    void messagesSentBeforeClosureRemainReadable() {
        Fixture fixture = assignedTicket();
        post(fixture.ticketId(), fixture.needyUser(), "история");
        jdbc.update("UPDATE tickets SET status = 'fulfilled', fulfilled_at = NOW() WHERE id = ?",
            fixture.ticketId());

        List<Map<String, Object>> history = controller.getMessages(
            fixture.ticketId(), 0, fixture.needyUser());

        assertThat(history).singleElement().satisfies(message ->
            assertThat(message).containsEntry("body", "история"));
    }

    private void assertClosureWins(String closedStatus, boolean clearAssignment) throws Exception {
        Fixture fixture = assignedTicket();
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch allowCloseCommit = new CountDownLatch(1);
        Future<?> closure = executor.submit(() -> tx.executeWithoutResult(ignored -> {
            String sql = clearAssignment
                ? "UPDATE tickets SET status = ?, assigned_volunteer_id = NULL WHERE id = ?"
                : "UPDATE tickets SET status = ? WHERE id = ?";
            jdbc.update(sql, closedStatus, fixture.ticketId());
            closed.countDown();
            await(allowCloseCommit);
        }));
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch messageStarted = new CountDownLatch(1);
        Future<ApiException> message = executor.submit(() -> {
            messageStarted.countDown();
            try {
                post(fixture.ticketId(), fixture.needyUser(), "слишком поздно");
                throw new AssertionError("Closed ticket accepted a message");
            } catch (ApiException expected) {
                return expected;
            }
        });
        assertThat(messageStarted.await(5, TimeUnit.SECONDS)).isTrue();
        allowCloseCommit.countDown();

        closure.get(5, TimeUnit.SECONDS);
        assertThat(message.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(400);
        assertThat(messageCount(fixture.ticketId())).isZero();
        verifyNoMessageNotifications();
    }

    private ApiException assignmentChangeWins(Fixture fixture, String sql, Object... values)
            throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch allowChangeCommit = new CountDownLatch(1);
        Future<?> assignmentChange = executor.submit(() -> tx.executeWithoutResult(ignored -> {
            Object[] args = new Object[values.length + 1];
            System.arraycopy(values, 0, args, 0, values.length);
            args[values.length] = fixture.ticketId();
            jdbc.update(sql, args);
            changed.countDown();
            await(allowChangeCommit);
        }));
        assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch messageStarted = new CountDownLatch(1);
        Future<ApiException> message = executor.submit(() -> {
            messageStarted.countDown();
            try {
                post(fixture.ticketId(), fixture.volunteerUser(), "старое назначение");
                throw new AssertionError("Former volunteer accepted as sender");
            } catch (ApiException expected) {
                return expected;
            }
        });
        assertThat(messageStarted.await(5, TimeUnit.SECONDS)).isTrue();
        allowChangeCommit.countDown();

        assignmentChange.get(5, TimeUnit.SECONDS);
        return message.get(5, TimeUnit.SECONDS);
    }

    private Fixture assignedTicket() {
        int needyId = insertNeedy("Получатель");
        int volunteerId = insertVolunteer("Волонтёр");
        int ticketId = jdbc.queryForObject(
            "INSERT INTO tickets (needy_id, items, address, lat, lon, status, created_at, "
                + "assigned_volunteer_id) VALUES (?, 'набор', 'адрес', 43.24, 76.90, "
                + "'assigned', NOW(), ?) RETURNING id",
            Integer.class, needyId, volunteerId);
        return new Fixture(ticketId, needyId, volunteerId);
    }

    private Map<String, Object> post(int ticketId, CurrentUser user, String body) {
        return controller.postMessage(ticketId, new MessageIn(body), user, null);
    }

    private int messageCount(int ticketId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM ticket_messages WHERE ticket_id = ?", Integer.class, ticketId);
    }

    private void verifyNoMessageNotifications() {
        verify(telegram, never()).notifyNeedy(anyInt(), anyString());
        verify(telegram, never()).notifyVolunteer(anyInt(), anyString());
        verify(push, never()).notifyRole(anyString(), anyInt(), anyString(), anyString());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out coordinating chat race");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating chat race", e);
        }
    }

    private record Fixture(int ticketId, int needyId, int volunteerId) {
        CurrentUser needyUser() {
            return new CurrentUser(101, "needy", "needy", needyId);
        }

        CurrentUser volunteerUser() {
            return new CurrentUser(102, "volunteer", "volunteer", volunteerId);
        }
    }
}
