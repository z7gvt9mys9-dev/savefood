package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.savefood.auth.AuthController;
import ru.savefood.auth.RefreshTokenService;
import ru.savefood.security.AuthArgumentResolver;
import ru.savefood.security.JwtService;
import ru.savefood.security.PasswordService;
import ru.savefood.web.GlobalExceptionHandler;
import ru.savefood.web.RateLimiter;
/** Focused integration tests for independent, rotating refresh sessions. */
class AuthRefreshIT extends PostgresIT {
    private static final String JWT_SECRET =
        "auth-refresh-integration-test-secret-0123456789";
    private final ObjectMapper mapper = new ObjectMapper();
    private final PasswordService passwords = new PasswordService();
    private JwtService jwt;
    private RefreshTokenService refreshTokens;
    private MockMvc mvc;
    private int userId;
    @BeforeEach
    void wireAuthentication() {
        jwt = new JwtService(JWT_SECRET);
        refreshTokens = new RefreshTokenService(jdbc, txManager);
        AuthController controller = new AuthController(
            jdbc, passwords, jwt, refreshTokens, new RateLimiter());
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new AuthArgumentResolver(jwt, jdbc))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        userId = insertUser("refresh-user", "password", "needy", insertNeedy("Recipient"));
    }
    @Test
    void loginIssuesShortAccessAndHashedRefreshCredentials() throws Exception {
        Tokens tokens = login();
        assertThat(jwt.decode(tokens.accessToken())).isNotNull();
        assertThat(tokens.refreshToken()).hasSize(43);
        long expiresAt = ((Number) jwt.payload(tokens.accessToken()).get("exp")).longValue();
        assertThat(expiresAt - Instant.now().getEpochSecond())
            .isBetween(14 * 60L, JwtService.ACCESS_TOKEN_EXPIRE_MINUTES * 60);
        assertThat(jdbc.queryForObject(
            "SELECT octet_length(token_hash) FROM refresh_sessions WHERE user_id = ?",
            Integer.class, userId)).isEqualTo(32);
        assertThat(jdbc.queryForObject(
            "SELECT session_id IS NOT NULL FROM refresh_sessions WHERE user_id = ?",
            Boolean.class, userId)).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT expires_at > NOW() + INTERVAL '29 days' FROM refresh_sessions WHERE user_id = ?",
            Boolean.class, userId)).isTrue();
    }
    @Test
    void expiredAccessDoesNotPreventRefreshButAccessCredentialAloneCannotRefresh() throws Exception {
        Tokens tokens = login();
        String expiredAccess = jwt.signClaims(Map.of("purpose", "expired-access"), -1);
        MvcResult refreshed = mvc.perform(post("/auth/refresh")
                .header("Authorization", "Bearer " + expiredAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(tokens.refreshToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        assertThat(body(refreshed).path("access_token").asText()).isNotBlank();
        assertThat(body(refreshed).path("refresh_token").asText())
            .isNotEqualTo(tokens.refreshToken());
        mvc.perform(post("/auth/refresh")
                .header("Authorization", "Bearer " + expiredAccess))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    @Test
    void rotationConsumesOldTokenAndReplayFails() throws Exception {
        Tokens original = login();
        Tokens rotated = refresh(original.refreshToken());
        assertThat(rotated.refreshToken()).isNotEqualTo(original.refreshToken());
        mvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(original.refreshToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertThat(refresh(rotated.refreshToken()).accessToken()).isNotBlank();
    }
    @Test
    void concurrentRotationHasExactlyOneWinner() throws Exception {
        String token = login().refreshToken();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RefreshTokenService.Rotation> first = executor.submit(
                () -> rotateTogether(token, ready, start));
            Future<RefreshTokenService.Rotation> second = executor.submit(
                () -> rotateTogether(token, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.Arrays.asList(first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS)))
                .filteredOn(java.util.Objects::nonNull)
                .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    void blockedAndDeletedUsersCannotRefresh() throws Exception {
        String blockedToken = login().refreshToken();
        jdbc.update("UPDATE users SET is_blocked = TRUE WHERE id = ?", userId);
        mvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(blockedToken)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        jdbc.update("UPDATE users SET is_blocked = FALSE WHERE id = ?", userId);
        String deletedToken = refreshTokens.issue(userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        assertRefreshRejected(deletedToken);
    }
    @Test
    void expiredRefreshTokenFails() throws Exception {
        String token = login().refreshToken();
        jdbc.update(
            "UPDATE refresh_sessions SET created_at = NOW() - INTERVAL '31 days', "
                + "expires_at = NOW() - INTERVAL '1 day' WHERE user_id = ?",
            userId);
        assertRefreshRejected(token);
    }
    @Test
    void logoutRevokesWholeRotatedSession() throws Exception {
        Tokens original = login();
        Tokens rotated = refresh(original.refreshToken());
        mvc.perform(post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(rotated.refreshToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertRefreshRejected(rotated.refreshToken());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM refresh_sessions WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class, userId)).isZero();
    }
    @Test
    void logoutWaitsForConcurrentRotationCommitAndRevokesSuccessor() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            Tokens original = login();
            CountDownLatch successorCreated = new CountDownLatch(1);
            CountDownLatch allowRotationCommit = new CountDownLatch(1);
            AtomicReference<String> successor = new AtomicReference<>();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> rotation = executor.submit(() -> tx.executeWithoutResult(status -> {
                    RefreshTokenService.Rotation result = refreshTokens.rotate(original.refreshToken());
                    assertThat(result).isNotNull();
                    successor.set(result.refreshToken());
                    successorCreated.countDown();
                    await(allowRotationCommit);
                }));
                assertThat(successorCreated.await(5, TimeUnit.SECONDS)).isTrue();
                Future<MvcResult> logout = executor.submit(() -> mvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(original.refreshToken())))
                    .andReturn());
                awaitBlockedDatabaseSession();
                allowRotationCommit.countDown();
                rotation.get(5, TimeUnit.SECONDS);
                MvcResult logoutResult = logout.get(5, TimeUnit.SECONDS);
                assertThat(logoutResult.getResponse().getStatus()).isEqualTo(200);
                assertThat(logoutResult.getResponse().getContentAsString())
                    .doesNotContainIgnoringCase("deadlock", "sql");
                assertRefreshRejected(successor.get());
            } finally {
                allowRotationCommit.countDown();
                executor.shutdownNow();
            }
        }
    }
    @Test
    void repeatedLogoutIsIdempotent() throws Exception {
        String token = login().refreshToken();
        refreshTokens.revokeSession(token);
        refreshTokens.revokeSession(token);
        assertRefreshRejected(token);
    }
    @Test
    void logoutRemovesOnlyPresentedBrowsersPushOwnership() throws Exception {
        String token = login().refreshToken();
        int otherUser = insertUser("other-push-user", "password", "needy", insertNeedy("Other"));
        String endpoint = "https://push.example/shared-browser";
        jdbc.update(
            "INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?, 'key', 'auth')",
            userId, endpoint);
        jdbc.update(
            "INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?, 'key', 'auth')",
            otherUser, "https://push.example/other-browser");
        mvc.perform(post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "refresh_token", token, "push_endpoint", endpoint))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM push_subscriptions WHERE endpoint = ?",
            Integer.class, endpoint)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?",
            Integer.class, otherUser)).isOne();
    }
    @Test
    void pushCleanupFailureCannotRollBackRefreshRevocation() throws Exception {
        String token = login().refreshToken();
        String endpoint = "https://push.example/failing-browser";
        jdbc.update(
            "INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?, 'key', 'auth')",
            userId, endpoint);
        jdbc.execute(
            "CREATE FUNCTION reject_push_delete() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced push cleanup failure'; END $$");
        jdbc.execute(
            "CREATE TRIGGER reject_push_delete BEFORE DELETE ON push_subscriptions "
                + "FOR EACH ROW EXECUTE FUNCTION reject_push_delete()");
        mvc.perform(post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "refresh_token", token, "push_endpoint", endpoint))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertRefreshRejected(token);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM push_subscriptions WHERE endpoint = ?",
            Integer.class, endpoint)).isOne();
    }
    private RefreshTokenService.Rotation rotateTogether(
        String token,
        CountDownLatch ready,
        CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        return refreshTokens.rotate(token);
    }
    private void assertRefreshRejected(String token) throws Exception {
        mvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(token)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    private void awaitBlockedDatabaseSession() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND pid <> pg_backend_pid() "
                    + "AND state = 'active' AND wait_event_type = 'Lock'",
                Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("logout never blocked behind the uncommitted rotation");
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test coordination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
    private Tokens login() throws Exception {
        MvcResult result = mvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "refresh-user")
                .param("password", "password"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        return tokens(body(result));
    }
    private Tokens refresh(String token) throws Exception {
        MvcResult result = mvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(token)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        return tokens(body(result));
    }
    private String refreshBody(String token) throws Exception {
        return mapper.writeValueAsString(Map.of("refresh_token", token));
    }
    private JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private static Tokens tokens(JsonNode body) {
        return new Tokens(
            body.path("access_token").asText(),
            body.path("refresh_token").asText());
    }
    private int insertUser(String username, String password, String role, int relatedId) {
        return jdbc.queryForObject(
            "INSERT INTO users (username, hashed_password, role, related_id) "
                + "VALUES (?, ?, ?, ?) RETURNING id",
            Integer.class, username, passwords.hash(password), role, relatedId);
    }
    private record Tokens(String accessToken, String refreshToken) {
    }
}
