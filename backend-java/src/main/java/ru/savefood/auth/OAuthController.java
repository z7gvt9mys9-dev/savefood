package ru.savefood.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import ru.savefood.security.Auth;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.JwtService;
import ru.savefood.web.ApiException;
import ru.savefood.web.ClientIp;
import ru.savefood.web.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Java port of backend/oauth_routes.py — social login / account linking through
 * Google, Yandex and Telegram, plus the {@code /auth/telegram/init-link} route
 * from backend/telegram_routes.py. Together with {@link AuthController} this
 * covers the whole {@code /auth} surface, so the nginx {@code /auth} location can
 * be flipped to the Java service as one unit.
 *
 * <p>Google/Yandex use the server-side authorization-code flow; the {@code state}
 * parameter is a short-lived signed JWT ({@link JwtService#signClaims}), so the
 * callback trusts the mode/user id without server-side session storage. Telegram
 * uses a bot deep-link for confirmation, a status-only browser token, and a
 * distinct one-time completion credential delivered by the bot.
 */
@RestController
@RequestMapping("/auth")
public class OAuthController {

    private static final int STATE_TTL_MINUTES = 10;
    private static final int LOGIN_COMPLETION_TTL_MINUTES = 5;
    private static final Pattern COMPLETION_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{32}");

    /** Per-provider OAuth endpoints, mirroring oauth_routes.py {@code PROVIDERS}. */
    private record ProviderCfg(String authUrl, String tokenUrl, String userinfoUrl,
                               String scope, String column) {
    }

    private static final Map<String, ProviderCfg> PROVIDERS = Map.of(
        "google", new ProviderCfg(
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://openidconnect.googleapis.com/v1/userinfo",
            "openid email", "google_id"),
        "yandex", new ProviderCfg(
            "https://oauth.yandex.ru/authorize",
            "https://oauth.yandex.ru/token",
            "https://login.yandex.ru/info?format=json",
            null, "yandex_id"));

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final RateLimiter rateLimiter;
    private final TelegramLoginService telegramLogin;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final String googleClientId;
    private final String googleClientSecret;
    private final String yandexClientId;
    private final String yandexClientSecret;
    private final String telegramBotToken;
    private final String botName;
    private final String publicUrl;

    public OAuthController(JdbcTemplate jdbc, JwtService jwt, RefreshTokenService refreshTokens,
                          RateLimiter rateLimiter,
                          TelegramLoginService telegramLogin,
                          @Value("${savefood.oauth.google-client-id:}") String googleClientId,
                          @Value("${savefood.oauth.google-client-secret:}") String googleClientSecret,
                          @Value("${savefood.oauth.yandex-client-id:}") String yandexClientId,
                          @Value("${savefood.oauth.yandex-client-secret:}") String yandexClientSecret,
                          @Value("${savefood.oauth.telegram-bot-token:}") String telegramBotToken,
                          @Value("${savefood.oauth.telegram-bot-name:savefood_bot}") String botName,
                          @Value("${savefood.oauth.public-url:http://localhost:3000}") String publicUrl) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.rateLimiter = rateLimiter;
        this.telegramLogin = telegramLogin;
        this.googleClientId = googleClientId;
        this.googleClientSecret = googleClientSecret;
        this.yandexClientId = yandexClientId;
        this.yandexClientSecret = yandexClientSecret;
        this.telegramBotToken = telegramBotToken;
        this.botName = botName;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    // ── Provider discovery (frontend hides unconfigured buttons) ────────────────

    @GetMapping("/oauth/providers")
    public Map<String, Object> listProviders() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("google", !googleClientId.isEmpty() && !googleClientSecret.isEmpty());
        out.put("yandex", !yandexClientId.isEmpty() && !yandexClientSecret.isEmpty());
        out.put("telegram", !telegramBotToken.isEmpty());
        return out;
    }

    // ── OAuth start / callback (Google, Yandex) ─────────────────────────────────

    @GetMapping("/oauth/{provider}/start")
    public Map<String, Object> oauthStart(@PathVariable String provider,
                                          @RequestParam(defaultValue = "login") String mode,
                                          @RequestParam(name = "next", defaultValue = "/") String next,
                                          HttpServletRequest request) {
        rateLimiter.check("auth:oauth_start", ClientIp.of(request), 10);
        ProviderCfg cfg = requireProvider(provider);
        if (!mode.equals("login") && !mode.equals("link")) {
            throw new ApiException(400, "mode must be 'login' or 'link'");
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("p", provider);
        state.put("m", mode);
        state.put("nxt", next.startsWith("/") ? next : "/");
        state.put("purpose", "oauth_state");

        if (mode.equals("link")) {
            // Linking attaches the provider id to the CURRENT account — require a
            // valid bearer token and pin the db user id inside the signed state.
            String header = request.getHeader("Authorization");
            String token = header != null && header.toLowerCase().startsWith("bearer ")
                ? header.substring(7) : null;
            CurrentUser principal = token != null ? jwt.decode(token) : null;
            if (principal == null) {
                throw new ApiException(401, "Авторизуйтесь, чтобы привязать аккаунт");
            }
            List<Boolean> active = jdbc.query("SELECT is_blocked FROM users WHERE id = ?",
                (rs, n) -> rs.getBoolean("is_blocked"), principal.userId());
            if (active.isEmpty()) {
                throw new ApiException(404, "User not found");
            }
            if (Boolean.TRUE.equals(active.get(0))) {
                throw new ApiException(403, "Аккаунт заблокирован администратором");
            }
            state.put("uid", principal.userId());
        }

        String clientId = provider.equals("google") ? googleClientId : yandexClientId;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri(provider));
        params.put("state", jwt.signClaims(state, STATE_TTL_MINUTES));
        if (cfg.scope() != null) {
            params.put("scope", cfg.scope());
        }
        return Map.of("url", cfg.authUrl() + "?" + formEncode(params));
    }

    @GetMapping("/oauth/{provider}/callback")
    public ResponseEntity<Void> oauthCallback(@PathVariable String provider,
                                              @RequestParam(required = false) String code,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String error) {
        ProviderCfg cfg = PROVIDERS.get(provider);
        if (cfg == null) {
            return frontError("Неизвестный провайдер");
        }
        if (error != null) {
            return frontError("Вход отменён на стороне провайдера");
        }
        Map<String, Object> st = jwt.readClaims(state == null ? "" : state);
        if (st == null || !"oauth_state".equals(st.get("purpose")) || !provider.equals(st.get("p"))) {
            return frontError("Сессия входа устарела — попробуйте ещё раз");
        }
        if (code == null) {
            return frontError("Провайдер не вернул код авторизации");
        }

        String[] identity;
        try {
            identity = fetchProviderIdentity(provider, cfg, code);
        } catch (Exception e) {
            return frontError("Не удалось получить данные у провайдера — попробуйте ещё раз");
        }
        String providerUid = identity[0];
        String column = cfg.column();

        if ("link".equals(st.get("m"))) {
            int uid = ((Number) st.get("uid")).intValue();
            List<Integer> other = jdbc.query(
                "SELECT id FROM users WHERE " + column + " = ? AND id != ?",
                (rs, n) -> rs.getInt("id"), providerUid, uid);
            if (!other.isEmpty()) {
                return frontError("Этот аккаунт уже привязан к другому пользователю SaveFood");
            }
            jdbc.update("UPDATE users SET " + column + " = ? WHERE id = ?", providerUid, uid);
            String nxt = st.get("nxt") != null ? st.get("nxt").toString() : "/";
            return redirect(publicUrl + nxt + "#linked=" + provider);
        }

        // mode == login
        List<Map<String, Object>> users = jdbc.query(
            "SELECT id, username, role, related_id, is_blocked FROM users WHERE " + column + " = ?",
            (rs, n) -> {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("id", rs.getInt("id"));
                u.put("username", rs.getString("username"));
                u.put("role", rs.getString("role"));
                u.put("related_id", rs.getObject("related_id"));
                u.put("is_blocked", rs.getBoolean("is_blocked"));
                return u;
            }, providerUid);
        if (users.isEmpty()) {
            return frontError("Аккаунт не найден. Войдите по паролю и привяжите " + provider + " в профиле");
        }
        Map<String, Object> user = users.get(0);
        if (Boolean.TRUE.equals(user.get("is_blocked"))) {
            return frontError("Аккаунт заблокирован администратором");
        }

        String completionToken = randomToken();
        jdbc.update(
            "INSERT INTO oauth_login_completions (token_hash, user_id, created_at) VALUES (?, ?, NOW())",
            hash(completionToken), ((Number) user.get("id")).intValue());
        jdbc.update(
            "DELETE FROM oauth_login_completions WHERE created_at < NOW() - (? * INTERVAL '1 minute')",
            LOGIN_COMPLETION_TTL_MINUTES * 6);
        // Only a short-lived, one-time exchange credential enters the fragment.
        // Access and refresh credentials are returned together by the POST below.
        return redirect(publicUrl + "/auth#oauth_completion=" + completionToken);
    }

    @PostMapping("/oauth/login/complete")
    public Map<String, Object> oauthLoginComplete(@RequestBody TelegramPoll payload,
                                                  HttpServletRequest request) {
        rateLimiter.check("auth:oauth_login_complete", ClientIp.of(request), 20);
        String token = payload == null ? null : payload.token();
        if (token == null || !COMPLETION_TOKEN_PATTERN.matcher(token).matches()) {
            throw new ApiException(401, "OAuth login session is invalid or expired");
        }
        List<Map<String, Object>> users = jdbc.query(
            """
            WITH consumed AS (
                DELETE FROM oauth_login_completions
                WHERE token_hash = ?
                RETURNING user_id, created_at
            )
            SELECT u.id, u.username, u.role, u.related_id
            FROM consumed c
            JOIN users u ON u.id = c.user_id
            WHERE c.created_at >= NOW() - (? * INTERVAL '1 minute')
              AND NOT u.is_blocked
            """,
            (rs, n) -> {
                Map<String, Object> user = new LinkedHashMap<>();
                user.put("id", rs.getInt("id"));
                user.put("username", rs.getString("username"));
                user.put("role", rs.getString("role"));
                user.put("related_id", rs.getObject("related_id"));
                return user;
            }, hash(token), LOGIN_COMPLETION_TTL_MINUTES);
        if (users.isEmpty()) {
            throw new ApiException(401, "OAuth login session is invalid or expired");
        }

        Map<String, Object> user = users.get(0);
        int userId = ((Number) user.get("id")).intValue();
        String role = (String) user.get("role");
        Integer relatedId = toInteger(user.get("related_id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", jwt.create(userId, (String) user.get("username"), role, relatedId));
        out.put("refresh_token", refreshTokens.issue(userId));
        out.put("token_type", "bearer");
        out.put("role", role);
        out.put("related_id", relatedId);
        return out;
    }

    // ── Link status / unlink (profile UI) ───────────────────────────────────────

    @GetMapping("/links")
    public Map<String, Object> linkStatus(@Auth CurrentUser user) {
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT telegram_chat_id, google_id, yandex_id FROM users WHERE id = ?",
            (rs, n) -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("telegram", rs.getObject("telegram_chat_id") != null);
                r.put("google", rs.getObject("google_id") != null);
                r.put("yandex", rs.getObject("yandex_id") != null);
                return r;
            }, user.userId());
        if (rows.isEmpty()) {
            throw new ApiException(404, "User not found");
        }
        return rows.get(0);
    }

    @PostMapping("/links/{provider}/unlink")
    public Map<String, Object> unlink(@PathVariable String provider, @Auth CurrentUser user) {
        Map<String, String> columns = Map.of(
            "telegram", "telegram_chat_id", "google", "google_id", "yandex", "yandex_id");
        String column = columns.get(provider);
        if (column == null) {
            throw new ApiException(404, "Unknown provider");
        }
        jdbc.update("UPDATE users SET " + column + " = NULL WHERE id = ?", user.userId());
        return Map.of("ok", true);
    }

    // ── Telegram login (status token + private one-time completion) ─────────────

    @PostMapping("/telegram/login/start")
    public Map<String, Object> telegramLoginStart(HttpServletRequest request) {
        rateLimiter.check("auth:tg_login_start", ClientIp.of(request), 10);
        if (telegramBotToken.isEmpty()) {
            throw new ApiException(503, "Вход через Telegram не настроен на сервере");
        }
        String token = telegramLogin.start();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("link", "https://t.me/" + botName + "?start=login_" + token);
        out.put("expires_in", TelegramLoginService.INITIAL_TTL_MINUTES * 60);
        return out;
    }

    @PostMapping("/telegram/login/poll")
    public Map<String, Object> telegramLoginPoll(@RequestBody TelegramPoll payload,
                                                 HttpServletRequest request) {
        rateLimiter.check("auth:tg_login_poll", ClientIp.of(request), 60);
        String token = payload == null ? null : payload.token();
        return Map.of("status", telegramLogin.status(token));
    }

    @PostMapping("/telegram/login/complete")
    public Map<String, Object> telegramLoginComplete(@RequestBody TelegramPoll payload,
                                                     HttpServletRequest request) {
        rateLimiter.check("auth:tg_login_complete", ClientIp.of(request), 20);
        String token = payload == null ? null : payload.token();
        TelegramLoginService.LoginUser user = telegramLogin.complete(token);
        if (user == null) {
            if (telegramLogin.completionMayActivate(token)) {
                throw new ApiException(409, "Ссылка для входа ещё активируется");
            }
            throw new ApiException(401, "Ссылка для входа недействительна или устарела");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("access_token", jwt.create(user.userId(), user.username(), user.role(), user.relatedId()));
        out.put("refresh_token", refreshTokens.issue(user.userId()));
        out.put("token_type", "bearer");
        out.put("role", user.role());
        out.put("related_id", user.relatedId());
        return out;
    }

    @PostMapping("/telegram/login/cancel")
    public Map<String, Object> telegramLoginCancel(@RequestBody TelegramPoll payload,
                                                   HttpServletRequest request) {
        rateLimiter.check("auth:tg_login_cancel", ClientIp.of(request), 20);
        telegramLogin.cancel(payload == null ? null : payload.token());
        return Map.of("ok", true);
    }

    // ── Telegram account linking (telegram_routes.py /auth/telegram/init-link) ──

    @GetMapping("/telegram/init-link")
    public Map<String, Object> initTelegramLink(@Auth CurrentUser user, HttpServletRequest request) {
        rateLimiter.check("auth:tg_init_link", ClientIp.of(request), 10);
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT id, telegram_chat_id FROM users WHERE id = ?",
            (rs, n) -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", rs.getInt("id"));
                r.put("already_linked", rs.getObject("telegram_chat_id") != null);
                return r;
            }, user.userId());
        if (rows.isEmpty()) {
            throw new ApiException(404, "User not found");
        }
        int userId = (int) rows.get(0).get("id");
        boolean alreadyLinked = (boolean) rows.get(0).get("already_linked");

        String token = randomToken();
        // UPSERT keeps the latest call's token authoritative in a single
        // statement (the unique index on user_id avoids the DELETE+INSERT race).
        jdbc.update(
            "INSERT INTO telegram_link_tokens (token, user_id, created_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET token = EXCLUDED.token, created_at = EXCLUDED.created_at",
            token, userId, OffsetDateTime.now(ZoneOffset.UTC));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("link", "https://t.me/" + botName + "?start=link_" + token);
        out.put("bot_name", botName);
        out.put("already_linked", alreadyLinked);
        out.put("expires_in", 600);
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ProviderCfg requireProvider(String provider) {
        ProviderCfg cfg = PROVIDERS.get(provider);
        if (cfg == null) {
            throw new ApiException(404, "Unknown provider");
        }
        boolean configured = provider.equals("google")
            ? !googleClientId.isEmpty() && !googleClientSecret.isEmpty()
            : !yandexClientId.isEmpty() && !yandexClientSecret.isEmpty();
        if (!configured) {
            throw new ApiException(503, "Вход через " + provider + " не настроен на сервере");
        }
        return cfg;
    }

    private String redirectUri(String provider) {
        return publicUrl + "/auth/oauth/" + provider + "/callback";
    }

    /** Exchanges the code for the provider's {@code (user_id, email)}. */
    private String[] fetchProviderIdentity(String provider, ProviderCfg cfg, String code)
            throws Exception {
        String clientId = provider.equals("google") ? googleClientId : yandexClientId;
        String clientSecret = provider.equals("google") ? googleClientSecret : yandexClientSecret;
        Map<String, String> data = new LinkedHashMap<>();
        data.put("grant_type", "authorization_code");
        data.put("code", code);
        data.put("client_id", clientId);
        data.put("client_secret", clientSecret);
        data.put("redirect_uri", redirectUri(provider));

        HttpRequest tokenReq = HttpRequest.newBuilder(URI.create(cfg.tokenUrl()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(data)))
                .build();
        HttpResponse<String> tokenResp = http.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        if (tokenResp.statusCode() != 200) {
            throw new IllegalStateException("token_exchange_failed");
        }
        String accessToken = mapper.readTree(tokenResp.body()).path("access_token").asText(null);
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalStateException("no_access_token");
        }

        String authScheme = provider.equals("google") ? "Bearer " : "OAuth ";
        HttpRequest infoReq = HttpRequest.newBuilder(URI.create(cfg.userinfoUrl()))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authScheme + accessToken)
                .GET()
                .build();
        HttpResponse<String> infoResp = http.send(infoReq, HttpResponse.BodyHandlers.ofString());
        if (infoResp.statusCode() != 200) {
            throw new IllegalStateException("userinfo_failed");
        }
        JsonNode j = mapper.readTree(infoResp.body());
        if (provider.equals("google")) {
            return new String[]{j.path("sub").asText(), j.path("email").asText(null)};
        }
        String email = j.path("default_email").asText(null);
        if (email == null) {
            email = j.path("login").asText(null);
        }
        return new String[]{j.path("id").asText(), email};
    }

    /** 24 random bytes, base64url without padding — secrets.token_urlsafe(24). */
    private String randomToken() {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String formEncode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private ResponseEntity<Void> frontError(String message) {
        // Mirror Python's quote(): percent-encode, spaces as %20 (not '+').
        String encoded = URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
        return redirect(publicUrl + "/auth#oauth_error=" + encoded);
    }

    private ResponseEntity<Void> redirect(String location) {
        // 307, matching Starlette's RedirectResponse default.
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    private static Integer toInteger(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
