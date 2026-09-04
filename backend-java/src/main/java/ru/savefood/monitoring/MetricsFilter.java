package ru.savefood.monitoring;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
@Component
public class MetricsFilter extends OncePerRequestFilter {
    private final MetricsService metrics;
    public MetricsFilter(MetricsService metrics) {
        this.metrics = metrics;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(req, resp);
        } finally {
            String route = (String) req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (route == null) {
                route = req.getRequestURI();
            }
            if (!"/metrics".equals(route)) {
                metrics.observe(req.getMethod(), route, resp.getStatus(),
                    (System.nanoTime() - started) / 1_000_000_000.0);
            }
        }
    }
}
