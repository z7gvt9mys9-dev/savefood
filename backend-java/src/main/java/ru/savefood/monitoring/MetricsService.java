package ru.savefood.monitoring;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class MetricsService {
    private static final double[] BUCKETS = {0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};
    private final JdbcTemplate jdbc;
    private final String metricsToken;
    private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final Map<String, Histogram> latencies = new ConcurrentHashMap<>();
    public MetricsService(JdbcTemplate jdbc, @Value("${savefood.metrics-token:}") String metricsToken) {
        this.jdbc = jdbc;
        this.metricsToken = metricsToken == null || metricsToken.isBlank() ? "" : metricsToken;
    }
    public boolean metricsAllowed(String token) {
        return !metricsToken.isEmpty() && token != null
            && MessageDigest.isEqual(metricsToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
    public void observe(String method, String route, int status, double seconds) {
        requests.computeIfAbsent(method + "\0" + route + "\0" + status, k -> new LongAdder()).increment();
        latencies.computeIfAbsent(method + "\0" + route, k -> new Histogram()).observe(seconds);
    }
    /** Full Prometheus text payload, refreshing the business gauges first. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP savefood_http_requests_total HTTP requests\n");
        sb.append("# TYPE savefood_http_requests_total counter\n");
        requests.forEach((k, v) -> {
            String[] p = k.split("\0", -1);
            sb.append("savefood_http_requests_total{method=\"").append(p[0])
              .append("\",route=\"").append(esc(p[1])).append("\",status=\"").append(p[2])
              .append("\"} ").append(v.sum()).append('\n');
        });
        sb.append("# HELP savefood_http_request_seconds HTTP request latency\n");
        sb.append("# TYPE savefood_http_request_seconds histogram\n");
        latencies.forEach((k, h) -> {
            String[] p = k.split("\0", -1);
            String labels = "method=\"" + p[0] + "\",route=\"" + esc(p[1]) + "\"";
            long cumulative = 0;
            for (int i = 0; i < BUCKETS.length; i++) {
                cumulative += h.buckets[i].sum();
                sb.append("savefood_http_request_seconds_bucket{").append(labels)
                  .append(",le=\"").append(trimDouble(BUCKETS[i])).append("\"} ").append(cumulative).append('\n');
            }
            sb.append("savefood_http_request_seconds_bucket{").append(labels)
              .append(",le=\"+Inf\"} ").append(h.count.sum()).append('\n');
            sb.append("savefood_http_request_seconds_sum{").append(labels).append("} ")
              .append(h.sum.sum()).append('\n');
            sb.append("savefood_http_request_seconds_count{").append(labels).append("} ")
              .append(h.count.sum()).append('\n');
        });
        appendBusinessGauges(sb);
        return sb.toString();
    }
    private void appendBusinessGauges(StringBuilder sb) {
        try {
            gauge(sb, "savefood_active_lots", "Lots currently on the map",
                "SELECT COUNT(*) FROM lots WHERE status = 'active'");
            gauge(sb, "savefood_open_tickets", "Tickets waiting for a volunteer",
                "SELECT COUNT(*) FROM tickets WHERE status = 'open'");
            gauge(sb, "savefood_routes_in_progress", "Active delivery routes",
                "SELECT COUNT(*) FROM volunteer_routes WHERE status = 'in_progress'");
            gauge(sb, "savefood_active_needy", "Recipient accounts available for assistance",
                "SELECT COUNT(*) FROM needy WHERE status = 'active'");
        } catch (RuntimeException e) {
        }
    }
    private void gauge(StringBuilder sb, String name, String help, String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(n == null ? 0 : n).append('\n');
    }
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private static String trimDouble(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
    private static final class Histogram {
        final LongAdder[] buckets = new LongAdder[BUCKETS.length];
        final LongAdder count = new LongAdder();
        final DoubleAdder sum = new DoubleAdder();
        Histogram() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }
        void observe(double seconds) {
            count.increment();
            sum.add(seconds);
            for (int i = 0; i < BUCKETS.length; i++) {
                if (seconds <= BUCKETS[i]) {
                    buckets[i].increment();
                    break;
                }
            }
        }
    }
}
