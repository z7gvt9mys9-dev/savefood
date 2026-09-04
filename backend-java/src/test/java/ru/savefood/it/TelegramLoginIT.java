package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.savefood.auth.OAuthController;
import ru.savefood.auth.RefreshTokenService;
import ru.savefood.auth.TelegramLoginService;
import ru.savefood.auth.TelegramPoll;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.JwtService;
import ru.savefood.telegram.TelegramBotService;
import ru.savefood.telegram.TelegramService;
import ru.savefood.telegram.TelegramWebhookController;
import ru.savefood.web.ApiException;
import ru.savefood.web.RateLimiter;
/** Regression coverage for the two-credential Telegram login protocol. */
class TelegramLoginIT extends PostgresIT {
    private static final String CHAT_ID = "424242";
    private static final String JWT_SECRET =
        "telegram-login-integration-test-secret-0123456789";
    private static final Pattern COMPLETION_PATTERN =
        Pattern.compile("telegram_completion=([A-Za-z0-9_-]{32})");
    private final ObjectMapper mapper = new ObjectMapper();
    private TelegramLoginService logins;
    private JwtService jwt;
    private OAuthController oauth;
    private TelegramService telegram;
    private TelegramBotService bot;
    private TelegramWebhookController webhook;
    @BeforeEach
    void wireTelegramLogin() {
        logins = new TelegramLoginService(jdbc);
        jwt = new JwtService(JWT_SECRET);
        oauth = new OAuthController(
            jdbc, jwt, new RefreshTokenService(jdbc), new RateLimiter(), logins,
            "", "", "", "", "configured-token", "savefood_test_bot",
            "https://savefood.test");
        telegram = mock(TelegramService.class);
        when(telegram.sendMessage(anyString(), anyString())).thenReturn(true);
        bot = new TelegramBotService(
            jdbc, telegram, logins, null, null, null, "", "https://savefood.test", "");
        webhook = new TelegramWebhookController(bot, "webhook-secret");
    }
    @Test
    void privateTelegramConfirmationCompletesLogin() throws Exception {
        UserFixture user = insertLinkedUser("telegram-victim");
        String initialToken = startToken();
        MockHttpServletRequest telegramRequest = request("149.154.167.220");
        telegramRequest.addHeader("X-Telegram-Bot-Api-Secret-Token", "webhook-secret");
        webhook.webhook(privateLoginUpdate(initialToken), telegramRequest);
        String completionToken = capturedCompletionToken(CHAT_ID);
        assertThat(completionToken).isNotEqualTo(initialToken);
        assertThat(jdbc.queryForObject(
            "SELECT completion_token_hash FROM telegram_login_tokens WHERE token = ?",
            String.class, initialToken)).isNotEqualTo(completionToken);
        Map<String, Object> poll = oauth.telegramLoginPoll(
            new TelegramPoll(initialToken), request("198.51.100.10"));
        assertThat(poll).containsExactly(Map.entry("status", "confirmed"));
        Map<String, Object> completed = oauth.telegramLoginComplete(
            new TelegramPoll(completionToken), request("198.51.100.11"));
        assertThat(completed).containsKeys(
            "access_token", "refresh_token", "token_type", "role", "related_id");
        CurrentUser principal = jwt.decode((String) completed.get("access_token"));
        assertThat(principal).isEqualTo(
            new CurrentUser(user.userId(), "telegram-victim", "needy", user.relatedId()));
    }
    @Test
    void pendingInitialTokenCanOnlyReportStatus() {
        insertLinkedUser("pending-user");
        String initialToken = startToken();
        assertThat(oauth.telegramLoginPoll(new TelegramPoll(initialToken), request("198.51.100.12")))
            .containsExactly(Map.entry("status", "pending"));
        assertCompletionRejected(initialToken);
        assertThat(logins.status(initialToken)).isEqualTo("pending");
    }
    @Test
    void confirmationNeverReturnsJwtToInitialPoller() {
        insertLinkedUser("confirmed-user");
        String initialToken = startToken();
        String completionToken = confirm(initialToken);
        assertThat(completionToken).isNotNull().isNotEqualTo(initialToken);
        Map<String, Object> poll = oauth.telegramLoginPoll(
            new TelegramPoll(initialToken), request("198.51.100.13"));
        assertThat(poll).containsExactly(Map.entry("status", "confirmed"));
        assertThat(poll).doesNotContainKeys("access_token", "token_type", "role", "related_id");
    }
    @Test
    void completionCredentialIsOneTimeAndReplayFails() {
        insertLinkedUser("one-time-user");
        String completionToken = confirmedCompletion();
        Map<String, Object> first = oauth.telegramLoginComplete(
            new TelegramPoll(completionToken), request("198.51.100.14"));
        assertThat(first.get("access_token")).isInstanceOf(String.class);
        assertCompletionRejected(completionToken);
        assertCompletionRejected(completionToken);
    }
    @Test
    void concurrentRedemptionCreatesExactlyOneSession() throws Exception {
        insertLinkedUser("concurrent-user");
        String completionToken = confirmedCompletion();
        int callers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return oauth.telegramLoginComplete(
                            new TelegramPoll(completionToken), request("203.0.113.20"));
                    } catch (ApiException rejected) {
                        assertThat(rejected.getStatus()).isEqualTo(401);
                        return null;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Map<String, Object>> successes = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                Map<String, Object> result = future.get(15, TimeUnit.SECONDS);
                if (result != null) successes.add(result);
            }
            assertThat(successes).hasSize(1);
            assertThat(successes.get(0).get("access_token")).isInstanceOf(String.class);
            assertCompletionRejected(completionToken);
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    void initialAndCompletionExpirationFailClosed() {
        insertLinkedUser("expiry-user");
        String expiredInitial = startToken();
        jdbc.update(
            "UPDATE telegram_login_tokens SET created_at = NOW() - INTERVAL '11 minutes' "
                + "WHERE token = ?",
            expiredInitial);
        assertThat(logins.status(expiredInitial)).isEqualTo("expired");
        assertThat(logins.confirm(expiredInitial, CHAT_ID)).isNull();
        String initialToken = startToken();
        String expiredCompletion = confirm(initialToken);
        jdbc.update(
            "UPDATE telegram_login_tokens SET completion_created_at = NOW() - INTERVAL '6 minutes' "
                + "WHERE token = ?",
            initialToken);
        assertThat(logins.status(initialToken)).isEqualTo("expired");
        assertCompletionRejected(expiredCompletion);
        assertThat(transactionCount(initialToken)).isZero();
    }
    @Test
    void blockedUserFailsClosedAndCredentialIsBurned() {
        UserFixture user = insertLinkedUser("blocked-user");
        String initialToken = startToken();
        String completionToken = confirm(initialToken);
        jdbc.update("UPDATE users SET is_blocked = true WHERE id = ?", user.userId());
        assertThat(logins.status(initialToken)).isEqualTo("expired");
        assertCompletionRejected(completionToken);
        jdbc.update("UPDATE users SET is_blocked = false WHERE id = ?", user.userId());
        assertCompletionRejected(completionToken);
    }
    @Test
    void unlinkedUserFailsClosedAndCredentialIsBurned() {
        UserFixture user = insertLinkedUser("unlinked-user");
        String initialToken = startToken();
        String completionToken = confirm(initialToken);
        jdbc.update("UPDATE users SET telegram_chat_id = NULL WHERE id = ?", user.userId());
        assertThat(logins.status(initialToken)).isEqualTo("expired");
        assertCompletionRejected(completionToken);
        jdbc.update("UPDATE users SET telegram_chat_id = ? WHERE id = ?", CHAT_ID, user.userId());
        assertCompletionRejected(completionToken);
    }
    @Test
    void deletedUserFailsClosed() {
        UserFixture user = insertLinkedUser("deleted-user");
        String initialToken = startToken();
        String completionToken = confirm(initialToken);
        jdbc.update("DELETE FROM users WHERE id = ?", user.userId());
        assertThat(logins.status(initialToken)).isEqualTo("expired");
        assertCompletionRejected(completionToken);
        assertThat(transactionCount(initialToken)).isZero();
    }
    @Test
    void cancellationRevokesPendingAndConfirmedTransactions() {
        insertLinkedUser("cancel-user");
        String pendingToken = startToken();
        oauth.telegramLoginCancel(new TelegramPoll(pendingToken), request("198.51.100.15"));
        oauth.telegramLoginCancel(new TelegramPoll(pendingToken), request("198.51.100.15"));
        assertThat(logins.status(pendingToken)).isEqualTo("expired");
        assertThat(logins.confirm(pendingToken, CHAT_ID)).isNull();
        String confirmedToken = startToken();
        String completionToken = confirm(confirmedToken);
        oauth.telegramLoginCancel(new TelegramPoll(confirmedToken), request("198.51.100.15"));
        assertThat(logins.status(confirmedToken)).isEqualTo("expired");
        assertCompletionRejected(completionToken);
    }
    @Test
    void nonPrivateConversationNeverReceivesCompletionCredential() throws Exception {
        insertLinkedUser("group-user");
        String initialToken = startToken();
        JsonNode update = mapper.readTree("""
            {"message":{"chat":{"id":424242,"type":"group"},
             "from":{"id":424242},"text":"/start login_%s"}}
            """.formatted(initialToken));
        bot.handleUpdate(update);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendMessage(eq(CHAT_ID), message.capture());
        assertThat(message.getValue()).doesNotContain("telegram_completion=");
        assertThat(logins.status(initialToken)).isEqualTo("pending");
    }
    @Test
    void mismatchedPrivateSenderNeverReceivesCompletionCredential() throws Exception {
        insertLinkedUser("sender-user");
        String initialToken = startToken();
        JsonNode update = mapper.readTree("""
            {"message":{"chat":{"id":424242,"type":"private"},
             "from":{"id":999999},"text":"/start login_%s"}}
            """.formatted(initialToken));
        bot.handleUpdate(update);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendMessage(eq(CHAT_ID), message.capture());
        assertThat(message.getValue()).doesNotContain("telegram_completion=");
        assertThat(logins.status(initialToken)).isEqualTo("pending");
    }
    @Test
    void telegramDeliveryFailureRevokesCompletion() throws Exception {
        insertLinkedUser("delivery-failure-user");
        String initialToken = startToken();
        when(telegram.sendMessage(anyString(), anyString())).thenReturn(false);
        bot.handleUpdate(privateLoginUpdate(initialToken));
        assertThat(logins.status(initialToken)).isEqualTo("expired");
        assertThat(transactionCount(initialToken)).isZero();
    }
    @Test
    void completionIsNotActiveUntilPrivateDeliverySucceeds() {
        insertLinkedUser("delivery-pending-user");
        String initialToken = startToken();
        String completionToken = confirmPending(initialToken);
        assertThat(logins.status(initialToken)).isEqualTo("pending");
        assertThatThrownBy(() -> oauth.telegramLoginComplete(
            new TelegramPoll(completionToken), request("198.51.100.18")))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(409));
        assertThat(transactionCount(initialToken)).isEqualTo(1);
        assertThat(logins.markDelivered(initialToken, completionToken)).isTrue();
        assertThat(logins.status(initialToken)).isEqualTo("confirmed");
        assertThat(oauth.telegramLoginComplete(
            new TelegramPoll(completionToken), request("198.51.100.18")))
            .containsKey("access_token");
    }
    @Test
    void blankOauthUrlUsesConfiguredSiteUrlForPrivateCompletion() throws Exception {
        insertLinkedUser("site-fallback-user");
        String initialToken = startToken();
        TelegramService fallbackTelegram = mock(TelegramService.class);
        when(fallbackTelegram.sendMessage(anyString(), anyString())).thenReturn(true);
        TelegramBotService fallbackBot = new TelegramBotService(
            jdbc, fallbackTelegram, logins, null, null, null,
            "", "", "https://custom.savefood.test");
        fallbackBot.handleUpdate(privateLoginUpdate(initialToken));
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(fallbackTelegram).sendMessage(eq(CHAT_ID), message.capture());
        assertThat(message.getValue())
            .contains("https://custom.savefood.test/auth#telegram_completion=");
        assertThat(logins.status(initialToken)).isEqualTo("confirmed");
    }
    @Test
    void invalidWebhookSecretCannotConfirmLogin() throws Exception {
        insertLinkedUser("webhook-secret-user");
        String initialToken = startToken();
        MockHttpServletRequest telegramRequest = request("149.154.167.220");
        telegramRequest.addHeader("X-Telegram-Bot-Api-Secret-Token", "wrong-secret");
        webhook.webhook(privateLoginUpdate(initialToken), telegramRequest);
        verifyNoInteractions(telegram);
        assertThat(logins.status(initialToken)).isEqualTo("pending");
    }
    @Test
    void schemaPreventsLegacyInitialTokenBinding() {
        UserFixture user = insertLinkedUser("legacy-user");
        String initialToken = startToken();
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE telegram_login_tokens SET user_id = ? WHERE token = ?",
            user.userId(), initialToken)).isInstanceOf(RuntimeException.class);
        assertThat(logins.status(initialToken)).isEqualTo("pending");
        assertCompletionRejected(initialToken);
    }
    @Test
    void oauthCompletionReturnsOneTimeAccessAndRefreshPair() throws Exception {
        UserFixture user = insertLinkedUser("oauth-completion-user");
        String completionToken = "abcdefghijklmnopqrstuvwxyzABCDEF";
        byte[] tokenHash = MessageDigest.getInstance("SHA-256")
            .digest(completionToken.getBytes(StandardCharsets.US_ASCII));
        jdbc.update(
            "INSERT INTO oauth_login_completions (token_hash, user_id, created_at) VALUES (?, ?, NOW())",
            tokenHash, user.userId());
        Map<String, Object> completed = oauth.oauthLoginComplete(
            new TelegramPoll(completionToken), request("198.51.100.30"));
        assertThat(completed).containsKeys(
            "access_token", "refresh_token", "token_type", "role", "related_id");
        assertThat(jwt.decode((String) completed.get("access_token")).userId()).isEqualTo(user.userId());
        assertThatThrownBy(() -> oauth.oauthLoginComplete(
            new TelegramPoll(completionToken), request("198.51.100.31")))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(401));
    }
    private UserFixture insertLinkedUser(String username) {
        int relatedId = insertNeedy(username);
        Integer userId = jdbc.queryForObject(
            "INSERT INTO users (username, hashed_password, role, related_id, telegram_chat_id) "
                + "VALUES (?, 'unused', 'needy', ?, ?) RETURNING id",
            Integer.class, username, relatedId, CHAT_ID);
        return new UserFixture(userId, relatedId);
    }
    private String startToken() {
        Map<String, Object> started = oauth.telegramLoginStart(request("198.51.100.1"));
        assertThat(started).containsKeys("token", "link", "expires_in");
        return (String) started.get("token");
    }
    private String confirmedCompletion() {
        String initialToken = startToken();
        return confirm(initialToken);
    }
    private String confirm(String initialToken) {
        String completionToken = confirmPending(initialToken);
        assertThat(logins.markDelivered(initialToken, completionToken)).isTrue();
        return completionToken;
    }
    private String confirmPending(String initialToken) {
        String completionToken = logins.confirm(initialToken, CHAT_ID);
        assertThat(completionToken).isNotNull();
        return completionToken;
    }
    private JsonNode privateLoginUpdate(String initialToken) throws Exception {
        return mapper.readTree("""
            {"message":{"chat":{"id":424242,"type":"private"},
             "from":{"id":424242},"text":"/start login_%s"}}
            """.formatted(initialToken));
    }
    private String capturedCompletionToken(String chatId) {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendMessage(eq(chatId), message.capture());
        Matcher matcher = COMPLETION_PATTERN.matcher(message.getValue());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
    private void assertCompletionRejected(String token) {
        assertThatThrownBy(() -> oauth.telegramLoginComplete(
            new TelegramPoll(token), request("198.51.100.99")))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(401));
    }
    private int transactionCount(String initialToken) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM telegram_login_tokens WHERE token = ?",
            Integer.class, initialToken);
    }
    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
    private record UserFixture(int userId, int relatedId) {
    }
}
