package ru.savefood.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import ru.savefood.web.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves an {@code @Auth CurrentUser} controller parameter, reproducing
 * auth.py {@code get_current_user}: decode the Bearer token (401 if
 * missing/invalid/expired) and reject blocked users (403). Unlike the
 * {@code /admin} surface — guarded path-wide by {@link AdminAuthFilter} — shop
 * endpoints mix public ({@code POST /shops/register}) and authenticated routes,
 * so authentication is applied per-parameter rather than by a path filter.
 */
@Component
public class AuthArgumentResolver implements HandlerMethodArgumentResolver {

    private final JwtService jwtService;
    private final JdbcTemplate jdbc;

    public AuthArgumentResolver(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Auth.class)
                && CurrentUser.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String header = request == null ? null : request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(401, "Could not validate credentials");
        }
        CurrentUser user = jwtService.decode(header.substring(7));
        if (user == null) {
            throw new ApiException(401, "Could not validate credentials");
        }
        // An already-issued token must stop working the moment the account is
        // blocked OR erased.  Previously an empty result was treated as allowed,
        // so deleting a user removed the login row but not their still-valid JWT.
        if (user.sub() == null || user.sub().isBlank()) {
            throw new ApiException(401, "Could not validate credentials");
        }
        List<Boolean> blocked = jdbc.query(
            "SELECT is_blocked FROM users WHERE username = ?",
            (rs, n) -> rs.getBoolean("is_blocked"), user.sub());
        if (blocked.isEmpty()) {
            throw new ApiException(401, "Could not validate credentials");
        }
        if (Boolean.TRUE.equals(blocked.get(0))) {
            throw new ApiException(403, "Аккаунт заблокирован администратором");
        }
        return user;
    }
}
