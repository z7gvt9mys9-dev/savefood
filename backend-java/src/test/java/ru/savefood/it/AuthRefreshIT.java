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
        refreshTokens = new RefreshTokenService(jdbc);
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
