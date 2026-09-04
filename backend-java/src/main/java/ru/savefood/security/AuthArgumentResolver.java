package ru.savefood.security;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import ru.savefood.web.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
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
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT username, role, related_id, is_blocked FROM users WHERE id = ?", user.userId());
        if (rows.isEmpty()) {
            throw new ApiException(401, "Could not validate credentials");
        }
        Map<String, Object> row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("is_blocked"))) {
            throw new ApiException(403, "Аккаунт заблокирован администратором");
        }
        Object relatedId = row.get("related_id");
        return new CurrentUser(user.userId(), (String) row.get("username"),
            (String) row.get("role"), relatedId instanceof Number n ? n.intValue() : null);
    }
}
