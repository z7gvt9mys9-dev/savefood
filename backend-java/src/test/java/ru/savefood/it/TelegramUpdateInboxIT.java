package ru.savefood.it;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.savefood.ai.AiService;
import ru.savefood.auth.TelegramLoginService;
import ru.savefood.chat.ChatService;
import ru.savefood.push.PushDispatchService;
import ru.savefood.telegram.*;

class TelegramUpdateInboxIT extends PostgresIT {
    private final ObjectMapper mapper = new ObjectMapper();
    private TelegramUpdateInbox inbox;
    private TelegramWebhookController webhook;
    private TelegramUpdateWorker worker;
    private TelegramBotService bot;
    private TelegramService telegram;
    private AiService ai;
    private PushDispatchService push;
    private ExecutorService pool;

    @BeforeEach
    void wire() {
        inbox = new TelegramUpdateInbox(jdbc, txManager, 3, 30, 120);
        webhook = new TelegramWebhookController(inbox, "secret");
        telegram = mock(TelegramService.class);
        when(telegram.sendMessage(anyString(), anyString())).thenReturn(true);
        ai = mock(AiService.class);
        push = mock(PushDispatchService.class);
        bot = new TelegramBotService(jdbc, telegram, new TelegramLoginService(jdbc),
            new ChatService(jdbc, txManager), ai, push, "support", "https://savefood.test", "");
        worker = new TelegramUpdateWorker(inbox, bot, 10);
        pool = Executors.newFixedThreadPool(3);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    void firstAndRepeatedDeliveryAreAcceptedOnceAndPersistOneChatMessage() throws Exception {
        int volunteer = assignedChat();
        assertThat(deliver(1, "hello")).isEqualTo(200);
        assertThat(deliver(1, "hello")).isEqualTo(200);
        assertThat(count("telegram_update_inbox")).isOne();
        assertThat(count("ticket_messages")).isZero();
        worker.drain();
        assertThat(deliver(1, "different payload must not overwrite the original")).isEqualTo(200);
        worker.drain();
        assertThat(count("ticket_messages")).isOne();
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages", String.class)).isEqualTo("hello");
        assertThat(inboxStatus(1)).isEqualTo("processed");
        assertThat(jdbc.queryForObject("SELECT payload FROM telegram_update_inbox", String.class)).isEqualTo("{}");
        verify(telegram).notifyVolunteer(volunteer, "◇ Получатель: hello");
        verify(push).notifyRole("volunteer", volunteer, "Сообщение от получателя: hello", "/volunteer");
        // This flow has no in-app notification insertion. Preserve that behavior.
        assertThat(count("notifications")).isZero();
    }

    @Test
    void concurrentWebhookDuplicatesProduceOneLogicalUpdate() throws Exception {
        assignedChat();
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> a = pool.submit(() -> { await(start); return deliver(2, "race"); });
        Future<Integer> b = pool.submit(() -> { await(start); return deliver(2, "race"); });
        start.countDown();
        assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS))).containsOnly(200);
        worker.drain();
        assertThat(count("telegram_update_inbox")).isOne();
        assertThat(count("ticket_messages")).isOne();
    }

    @Test
    void duplicateSupportUpdateEscalatesOnceWithoutInventingPersistentNotifications() throws Exception {
        when(ai.askSupportAi(anyString(), any(), any())).thenReturn(AiService.ESCALATE);
        deliver(3, "support question");
        deliver(3, "support question");
        worker.drain();
        worker.drain();
        verify(ai).askSupportAi("support question", null, null);
        verify(telegram).sendMessage(eq("support"), contains("support question"));
        assertThat(count("notifications")).isZero();
        assertThat(count("ticket_messages")).isZero();
        assertThat(inboxStatus(3)).isEqualTo("processed");
    }

    @Test
    void crashAfterChatInsertBeforeCompletionRollsBackBothAndRetryCreatesOneMessage() throws Exception {
        assignedChat();
        deliver(4, "crash");
        var claim = inbox.claim();
        assertThatThrownBy(() -> inbox.process(claim, payload -> {
            process(payload);
            assertThat(count("ticket_messages")).isOne();
            // An abrupt worker failure must not leave a committed message behind.
            throw new AssertionError("simulated worker death");
        })).isInstanceOf(AssertionError.class);
        assertThat(count("ticket_messages")).isZero();
        assertThat(inboxStatus(4)).isEqualTo("processing");
        due(4);
        worker.drain();
        assertThat(count("ticket_messages")).isOne();
        assertThat(inboxStatus(4)).isEqualTo("processed");
        assertThat(inbox.claim()).isNull();
    }

    @Test
    void completionAndDurableSideEffectCannotBeSeparatedByCrashOrLostAcknowledgement() throws Exception {
        assignedChat();
        deliver(5, "committed");
        var claim = inbox.claim();
        inbox.process(claim, this::process);
        // Simulate losing all worker state after commit and replaying the same claim/delivery.
        inbox.process(claim, ignored -> { throw new AssertionError("reprocessed committed work"); });
        deliver(5, "committed");
        worker.drain();
        assertThat(count("ticket_messages")).isOne();
        assertThat(inboxStatus(5)).isEqualTo("processed");
    }

    @Test
    void failuresBackOffAndStopAtConfiguredAttemptLimit() throws Exception {
        when(ai.askSupportAi(anyString(), any(), any())).thenThrow(new IllegalStateException("secret text"));
        deliver(6, "fail");
        for (int i = 1; i <= 3; i++) {
            worker.drain();
            assertThat(jdbc.queryForObject("SELECT attempts FROM telegram_update_inbox", Integer.class)).isEqualTo(i);
            assertThat(inbox.claim()).isNull();
            assertThat(jdbc.queryForObject("SELECT last_error FROM telegram_update_inbox", String.class))
                .isEqualTo("IllegalStateException");
            due(6);
        }
        worker.drain();
        assertThat(inboxStatus(6)).isEqualTo("failed");
        verify(ai, times(3)).askSupportAi("fail", null, null);
    }

    @Test
    void crashesAlsoExhaustAttemptsAndStaleClaimsCannotRun() throws Exception {
        deliver(7, "/help");
        var first = inbox.claim();
        due(7);
        var second = inbox.claim();
        inbox.process(first, ignored -> { throw new AssertionError("stale claim ran"); });
        assertThat(second.attempt()).isEqualTo(2);
        due(7);
        assertThat(inbox.claim().attempt()).isEqualTo(3);
        due(7);
        assertThat(inbox.claim()).isNull();
        assertThat(inboxStatus(7)).isEqualTo("failed");
    }

    @Test
    void blockedAiDoesNotDelayWebhookOrAllowAnotherWorkerToStealLiveClaim() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ai.askSupportAi(anyString(), any(), any())).thenAnswer(ignored -> {
            entered.countDown(); await(release); return "answer";
        });
        assertThat(deliver(8, "slow AI")).isEqualTo(200);
        verifyNoInteractions(ai);
        var claim = inbox.claim();
        // Expire the lease BEFORE processing takes its row lock to test a live, overdue worker.
        due(8);
        Future<?> processing = pool.submit(() -> inbox.process(claim, this::process));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pool.submit(() -> deliver(8, "slow AI")).get(2, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(pool.submit(inbox::claim).get(2, TimeUnit.SECONDS)).isNull();
            assertThat(pool.submit(() -> deliver(9, "/help")).get(2, TimeUnit.SECONDS)).isEqualTo(200);
            var other = pool.submit(inbox::claim).get(2, TimeUnit.SECONDS);
            assertThat(other.updateId()).isEqualTo(9);
            inbox.process(other, this::process);
        } finally {
            release.countDown();
        }
        processing.get(5, TimeUnit.SECONDS);
        assertThat(inboxStatus(8)).isEqualTo("processed");
        assertThat(inboxStatus(9)).isEqualTo("processed");
    }

    @Test
    void independentWorkersDivideConcurrentClaims() throws Exception {
        for (long id = 10; id < 20; id++) deliver(id, "/help");
        CountDownLatch start = new CountDownLatch(1);
        var otherInbox = new TelegramUpdateInbox(jdbc, txManager, 3, 30, 120);
        var otherWorker = new TelegramUpdateWorker(otherInbox, bot, 5);
        var firstWorker = new TelegramUpdateWorker(inbox, bot, 5);
        Future<?> first = pool.submit(() -> { await(start); firstWorker.drain(); });
        Future<?> second = pool.submit(() -> { await(start); otherWorker.drain(); });
        start.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telegram_update_inbox WHERE status = 'processed' AND attempts = 1",
            Integer.class)).isEqualTo(10);
        verify(telegram, times(10)).sendMessage(eq("42"), anyString());
    }

    @Test
    void malformedAndOversizeInputNeverEntersInbox() throws Exception {
        for (String json : List.of("{}", "null", "[]", "{", "{\"update_id\":null}",
                "{\"update_id\":-1}", "{\"update_id\":1.5}", "{\"update_id\":\"1\"}",
                "{\"update_id\":9223372036854775808}", "{\"update_id\":1} {}")) {
            assertThat(webhook.webhook(request(json, "secret")).getStatusCode().value()).isEqualTo(400);
        }
        String large = " ".repeat(TelegramUpdateInbox.MAX_PAYLOAD_BYTES + 1);
        assertThat(webhook.webhook(request(large, "secret")).getStatusCode().value()).isEqualTo(413);
        var chunked = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
        };
        chunked.setContent(large.getBytes(StandardCharsets.UTF_8));
        chunked.addHeader("X-Telegram-Bot-Api-Secret-Token", "secret");
        assertThat(webhook.webhook(chunked).getStatusCode().value()).isEqualTo(413);
        assertThat(webhook.webhook(request(large, "wrong")).getStatusCode().value()).isEqualTo(200);
        assertThat(new TelegramWebhookController(inbox, "").webhook(request(large, "secret"))
            .getStatusCode().value()).isEqualTo(200);
        assertThat(count("telegram_update_inbox")).isZero();
    }

    @Test
    void linkUpdateStillConsumesTokenAndLinksAccountOnce() throws Exception {
        int needy = insertNeedy("link");
        int user = jdbc.queryForObject("INSERT INTO users (username, hashed_password, role, related_id) "
            + "VALUES ('link-user', 'unused', 'needy', ?) RETURNING id", Integer.class, needy);
        jdbc.update("INSERT INTO telegram_link_tokens (token, user_id, created_at) VALUES ('link-token', ?, NOW())", user);
        deliver(20, "/start link_link-token");
        deliver(20, "/start link_link-token");
        worker.drain();
        assertThat(jdbc.queryForObject("SELECT telegram_chat_id FROM users WHERE id = ?", String.class, user)).isEqualTo("42");
        assertThat(count("telegram_link_tokens")).isZero();
        verify(telegram).sendMessage(eq("42"), contains("Telegram привязан"));
    }

    private int assignedChat() {
        int needy = insertNeedy("chat");
        int volunteer = insertVolunteer("counterpart");
        jdbc.update("INSERT INTO users (username, hashed_password, role, related_id, telegram_chat_id) "
            + "VALUES ('chat-user', 'unused', 'needy', ?, '42')", needy);
        jdbc.update("INSERT INTO tickets (needy_id, items, address, lat, lon, status, assigned_volunteer_id, created_at) "
            + "VALUES (?, 'food', 'address', 43, 76, 'assigned', ?, NOW())", needy, volunteer);
        return volunteer;
    }

    private int deliver(long id, String text) throws Exception {
        ObjectNode update = mapper.createObjectNode().put("update_id", id);
        var message = update.putObject("message").put("text", text);
        message.putObject("chat").put("id", 42).put("type", "private");
        message.putObject("from").put("id", 42);
        return webhook.webhook(request(update.toString(), "secret")).getStatusCode().value();
    }

    private MockHttpServletRequest request(String body, String secret) {
        var request = new MockHttpServletRequest("POST", "/telegram/webhook");
        request.setContentType("application/json");
        request.addHeader("X-Telegram-Bot-Api-Secret-Token", secret);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private void process(String payload) {
        try { bot.processUpdate(mapper.readTree(payload)); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    private void due(long id) {
        jdbc.update("UPDATE telegram_update_inbox SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE update_id = ?", id);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String inboxStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM telegram_update_inbox WHERE update_id = ?", String.class, id);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
