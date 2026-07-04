package ru.savefood.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.savefood.security.Auth;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.JwtService;
import ru.savefood.security.PasswordService;
import ru.savefood.web.ApiException;
import ru.savefood.web.RateLimiter;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Java port of backend/auth_routes.py — token issuance for the whole platform.
 * Wire-compatible with the Python endpoints: {@code POST /auth/login}
 * (OAuth2 password flow, form-encoded, rate-limited 5/min/IP),
 * {@code POST /auth/refresh} and {@code GET /auth/me}. Tokens minted here are
 * interchangeable with the FastAPI backend's ({@link JwtService}).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JdbcTemplate jdbc;
    private final PasswordService passwords;
    private final JwtService jwt;
    private final RateLimiter rateLimiter;

    public AuthController(JdbcTemplate jdbc, PasswordService passwords, JwtService jwt,
                          RateLimiter rateLimiter) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.jwt = jwt;
        this.rateLimiter = rateLimiter;
    }

    /**
     * OAuth2 password flow: form fields {@code username}/{@code password}.
     * Accepts both {@code application/x-www-form-urlencoded} and
     * {@code multipart/form-data} — the React auth form posts a {@code FormData}
     * (multipart), while the FastAPI original's {@code OAuth2PasswordRequestForm}
     * took either; restricting to urlencoded broke UI login with a 415.
     */
    @PostMapping(path = "/login", consumes = {
        MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        MediaType.MULTIPART_FORM_DATA_VALUE
    })
    public Map<String, Object> login(@RequestParam("username") String username,
                                     @RequestParam("password") String password,
                                     HttpServletRequest request) {
        rateLimiter.check("auth:login", request.getRemoteAddr(), 5);

        List<Map<String, Object>> rows = jdbc.query(
            "SELECT hashed_password, role, related_id, is_blocked FROM users WHERE username = ?",
            (rs, n) -> {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("hashed_password", rs.getString("hashed_password"));
                u.put("role", rs.getString("role"));
                u.put("related_id", rs.getObject("related_id"));
                u.put("is_blocked", rs.getBoolean("is_blocked"));
                return u;
            }, username);

        Map<String, Object> user = rows.isEmpty() ? null : rows.get(0);
        if (user == null || !passwords.verify(password, (String) user.get("hashed_password"))) {
            throw new ApiException(401, "Incorrect username or password");
        }
        if (Boolean.TRUE.equals(user.get("is_blocked"))) {
            throw new ApiException(403, "Аккаунт заблокирован администратором");
        }

        String role = (String) user.get("role");
        Integer relatedId = toInteger(user.get("related_id"));
        String accessToken = jwt.create(username, role, relatedId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", accessToken);
        out.put("token_type", "bearer");
        out.put("role", role);
        out.put("related_id", relatedId);
        return out;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Auth CurrentUser user) {
        String accessToken = jwt.create(user.sub(), user.role(), user.relatedId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", accessToken);
        out.put("token_type", "bearer");
        return out;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@Auth CurrentUser user, HttpServletRequest request) {
        // @Auth has already validated the token; re-read it to return the full
        // payload (incl. exp) verbatim, as auth.py read_users_me does.
        String header = request.getHeader("Authorization");
        return jwt.payload(header.substring(7));
    }

    private static Integer toInteger(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
