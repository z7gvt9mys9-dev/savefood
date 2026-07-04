package ru.savefood.config;

import ru.savefood.security.AdminAuthFilter;
import ru.savefood.security.JwtService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class SecurityConfig {

    /** Registers {@link AdminAuthFilter} for the admin surface only. */
    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(JwtService jwtService, JdbcTemplate jdbc) {
        FilterRegistrationBean<AdminAuthFilter> reg = new FilterRegistrationBean<>(new AdminAuthFilter(jwtService, jdbc));
        reg.addUrlPatterns("/admin/*");
        reg.setName("adminAuthFilter");
        return reg;
    }
}
