package ru.savefood.config;
import java.util.List;
import ru.savefood.security.AdminArgumentResolver;
import ru.savefood.security.AuthArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AdminArgumentResolver adminArgumentResolver;
    private final AuthArgumentResolver authArgumentResolver;
    public WebConfig(AdminArgumentResolver adminArgumentResolver,
                     AuthArgumentResolver authArgumentResolver) {
        this.adminArgumentResolver = adminArgumentResolver;
        this.authArgumentResolver = authArgumentResolver;
    }
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(adminArgumentResolver);
        resolvers.add(authArgumentResolver);
    }
}
