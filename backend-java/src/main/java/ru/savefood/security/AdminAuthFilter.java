package ru.savefood.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces admin access for every {@code /admin/*} request, reproducing the
 * Python dependency chain {@code get_current_user → require_admin}:
 * <ol>
 *   <li>missing/invalid Bearer token → 401 "Could not validate credentials"</li>
 *   <li>user is blocked → 403 "Аккаунт заблокирован администратором"</li>
 *   <li>role != admin → 403 "Admin access required"</li>
 * </ol>
 * On success the validated {@link CurrentUser} is stashed as a request attribute
 * for {@link AdminArgumentResolver}. Failures are written as FastAPI-style
 * {@code {"detail": ...}} JSON directly here (deterministic, independent of MVC
 * exception handling).
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String ATTR = "savefood.currentUser";

    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminAuthFilter(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            deny(response, 401, "Could not validate credentials");
            return;
        }
        CurrentUser user = jwtService.decode(header.substring(7));
        if (user == null) {
            deny(response, 401, "Could not validate credentials");
            return;
        }
        // Reject blocked users on every request — an already-issued token would
        // otherwise keep working until expiry (auth.py get_current_user).
        if (user.sub() != null) {
            List<Boolean> blocked = jdbc.query(
                "SELECT is_blocked FROM users WHERE username = ?",
                (rs, n) -> rs.getBoolean("is_blocked"), user.sub());
            if (!blocked.isEmpty() && Boolean.TRUE.equals(blocked.get(0))) {
                deny(response, 403, "Аккаунт заблокирован администратором");
                return;
            }
        }
        if (!user.isAdmin()) {
            deny(response, 403, "Admin access required");
            return;
        }
        request.setAttribute(ATTR, user);
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (status == 401) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.getWriter().write(mapper.writeValueAsString(Map.of("detail", detail)));
    }
}
