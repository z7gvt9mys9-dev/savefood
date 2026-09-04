package ru.savefood.it;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import ru.savefood.auth.AuthController;
import ru.savefood.auth.OAuthController;
import ru.savefood.auth.RefreshTokenService;
import ru.savefood.auth.TelegramLoginService;
import ru.savefood.security.AuthArgumentResolver;
import ru.savefood.security.JwtService;
import ru.savefood.security.PasswordService;
import ru.savefood.web.GlobalExceptionHandler;
import ru.savefood.web.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
/** Regression coverage for immutable account identity in access tokens. */
class AuthIdentityIT extends PostgresIT {
    private static final String JWT_SECRET =
        "auth-identity-integration-test-secret-0123456789";
    private final ObjectMapper mapper = new ObjectMapper();
    private final PasswordService passwords = new PasswordService();
    private MockMvc mvc;
    @BeforeEach
    void wireAuthentication() {
        JwtService jwt = new JwtService(JWT_SECRET);
        RefreshTokenService refreshTokens = new RefreshTokenService(jdbc);
        RateLimiter rateLimiter = new RateLimiter();
        AuthController auth = new AuthController(jdbc, passwords, jwt, refreshTokens, rateLimiter);
        OAuthController oauth = new OAuthController(
            jdbc, jwt, refreshTokens, rateLimiter, new TelegramLoginService(jdbc),
            "", "", "", "", "configured-token", "savefood_test_bot",
            "https://savefood.test");
        mvc = MockMvcBuilders.standaloneSetup(auth, oauth)
            .setCustomArgumentResolvers(new AuthArgumentResolver(jwt, jdbc))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }
    @Test
    void deletedUsernameReuseCannotTransferOrRefreshOldIdentity() throws Exception {
        int userA = insertUser("reused-name", "password-a", "needy", insertNeedy("A"));
        Tokens old = login("reused-name", "password-a");
        jdbc.update("DELETE FROM users WHERE id = ?", userA);
        int userB = insertUser("reused-name", "password-b", "needy", insertNeedy("B"));
        assertThat(userB).isNotEqualTo(userA);
        mvc.perform(get("/auth/me").header("Authorization", bearer(old.accessToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(old.refreshToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(get("/auth/telegram/init-link").header("Authorization", bearer(old.accessToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM telegram_link_tokens", Integer.class)).isZero();
        Tokens current = login("reused-name", "password-b");
        mvc.perform(get("/auth/me").header("Authorization", bearer(current.accessToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(jsonPath("$.sub").value("reused-name"))
            .andExpect(jsonPath("$.role").value("needy"));
        mvc.perform(get("/auth/telegram/init-link").header("Authorization", bearer(current.accessToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM telegram_link_tokens WHERE user_id = ?", Integer.class, userB)).isOne();
        MvcResult refreshed = mvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(current.refreshToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        String refreshedToken = mapper.readTree(refreshed.getResponse().getContentAsString())
            .path("access_token").asText();
        mvc.perform(get("/auth/me").header("Authorization", bearer(refreshedToken)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        jdbc.update("UPDATE users SET is_blocked = TRUE WHERE id = ?", userB);
        mvc.perform(get("/auth/me").header("Authorization", bearer(current.accessToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        String rotatedRefresh = mapper.readTree(refreshed.getResponse().getContentAsString())
            .path("refresh_token").asText();
        mvc.perform(post("/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(rotatedRefresh)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        jdbc.update("DELETE FROM users WHERE id = ?", userB);
        mvc.perform(get("/auth/me").header("Authorization", bearer(current.accessToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    private int insertUser(String username, String password, String role, int relatedId) {
        return jdbc.queryForObject(
            "INSERT INTO users (username, hashed_password, role, related_id) "
                + "VALUES (?, ?, ?, ?) RETURNING id",
            Integer.class, username, passwords.hash(password), role, relatedId);
    }
    private Tokens login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", username)
                .param("password", password))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        return new Tokens(body.path("access_token").asText(), body.path("refresh_token").asText());
    }
    private String refreshBody(String refreshToken) throws Exception {
        return mapper.writeValueAsString(Map.of("refresh_token", refreshToken));
    }
    private static String bearer(String token) {
        return "Bearer " + token;
    }
    private record Tokens(String accessToken, String refreshToken) {
    }
}
