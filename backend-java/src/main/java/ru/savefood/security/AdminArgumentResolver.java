package ru.savefood.security;

import jakarta.servlet.http.HttpServletRequest;
import ru.savefood.web.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies the {@code @Admin CurrentUser} controller parameter from the request
 * attribute set by {@link AdminAuthFilter}. The filter has already validated
 * admin access for every {@code /admin/*} route, so this only reads the result;
 * a missing attribute means the filter was bypassed and is a server error.
 */
@Component
public class AdminArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Admin.class)
                && CurrentUser.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object user = request != null ? request.getAttribute(AdminAuthFilter.ATTR) : null;
        if (user instanceof CurrentUser cu) {
            return cu;
        }
        throw new ApiException(500, "Admin authentication filter did not run");
    }
}
