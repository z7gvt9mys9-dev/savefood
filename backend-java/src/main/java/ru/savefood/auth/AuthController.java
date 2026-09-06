package ru.savefood.auth;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.savefood.security.Auth;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.JwtService;
import ru.savefood.security.PasswordService;
import ru.savefood.web.ApiException;
import ru.savefood.web.ClientIp;
import ru.savefood.web.RateLimiter;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordService passwords;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final RateLimiter rateLimiter;
    public AuthController(JdbcTemplate jdbc, PasswordService passwords, JwtService jwt,
                          RefreshTokenService refreshTokens,
                          RateLimiter rateLimiter) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.rateLimiter = rateLimiter;
    }
    @PostMapping(path = "/login", consumes = {
        MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        MediaType.MULTIPART_FORM_DATA_VALUE
    })
    public Map<String, Object> login(@RequestParam("username") String username,
                                     @RequestParam("password") String password,
                                     @RequestParam(value = "role", required = false) String expectedRole,
                                     HttpServletRequest request) {
        rateLimiter.check("auth:login", ClientIp.of(request), 5);
        if (expectedRole != null && !List.of("shop", "volunteer", "needy", "admin").contains(expectedRole)) {
            throw new ApiException(400, "Unknown account role");
        }
        List<Map<String, Object>> rows = jdbc.query(
            expectedRole == null
                ? "SELECT id, username, hashed_password, role, related_id, is_blocked FROM users WHERE username = ?"
                : "SELECT id, username, hashed_password, role, related_id, is_blocked FROM users WHERE username = ? AND role = ?",
            (rs, n) -> {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("id", rs.getInt("id"));
                u.put("username", rs.getString("username"));
                u.put("hashed_password", rs.getString("hashed_password"));
                u.put("role", rs.getString("role"));
                u.put("related_id", rs.getObject("related_id"));
                u.put("is_blocked", rs.getBoolean("is_blocked"));
                return u;
            }, expectedRole == null ? new Object[] { username } : new Object[] { username, expectedRole });
        Map<String, Object> user = rows.isEmpty() ? null : rows.get(0);
        if (user == null || !passwords.verify(password, (String) user.get("hashed_password"))) {
            throw new ApiException(401, "Incorrect username or password");
        }
        if (Boolean.TRUE.equals(user.get("is_blocked"))) {
            throw new ApiException(403, "Аккаунт заблокирован администратором");
        }
        String role = (String) user.get("role");
        Integer relatedId = toInteger(user.get("related_id"));
        String accessToken = jwt.create(((Number) user.get("id")).intValue(),
            (String) user.get("username"), role, relatedId);
        String refreshToken = refreshTokens.issue(((Number) user.get("id")).intValue());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", accessToken);
        out.put("refresh_token", refreshToken);
        out.put("token_type", "bearer");
        out.put("role", role);
        out.put("related_id", relatedId);
        return out;
    }
    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody(required = false) RefreshRequest request) {
        RefreshTokenService.Rotation rotation = request == null
            ? null : refreshTokens.rotate(request.refreshToken());
        if (rotation == null) {
            if (request != null && refreshTokens.isActiveTokenForBlockedUser(request.refreshToken())) {
                throw new ApiException(403, "Аккаунт заблокирован администратором");
            }
            throw new ApiException(401, "Invalid or expired refresh token");
        }
        String accessToken = jwt.create(rotation.userId(), rotation.username(),
            rotation.role(), rotation.relatedId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", accessToken);
        out.put("refresh_token", rotation.refreshToken());
        out.put("token_type", "bearer");
        return out;
    }
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody(required = false) RefreshRequest request) {
        if (request != null) {
            refreshTokens.revokeSession(request.refreshToken(), request.pushEndpoint());
        }
        return Map.of("ok", true);
    }
    @GetMapping("/me")
    public Map<String, Object> me(@Auth CurrentUser user, HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return jwt.payload(header.substring(7));
    }
    private static Integer toInteger(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
    public record RefreshRequest(
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("push_endpoint") String pushEndpoint
    ) {
    }
}
