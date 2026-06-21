package kz.savefood.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;
import kz.savefood.cache.CacheService;
import kz.savefood.esg.EsgService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App-level operational endpoints, 1:1 with the ones defined in backend/main.py:
 * {@code GET /metrics} (Prometheus scrape, token-gated), {@code /healthz}
 * (liveness — no dependency checks), {@code /readyz} (readiness — verifies the DB)
 * and {@code /stats} (public landing-page counters, short-TTL cached).
 */
@RestController
public class MonitoringController {

    private final MetricsService metrics;
    private final JdbcTemplate jdbc;
    private final CacheService cache;

    public MonitoringController(MetricsService metrics, JdbcTemplate jdbc, CacheService cache) {
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.cache = cache;
    }

    @GetMapping("/metrics")
    public ResponseEntity<String> metrics(@RequestParam(value = "token", required = false) String token,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        String t = token == null ? "" : token;
        if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
            t = t.isEmpty() ? auth.substring(7) : t;
        }
        if (!metrics.metricsAllowed(t)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"Forbidden\"}");
        }
        // Prometheus text exposition format (version 0.0.4).
        return ResponseEntity.ok()
            .header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            .body(metrics.render());
    }

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        // Liveness: deliberately checks no dependencies so a transient DB blip
        // doesn't get a healthy app restarted.
        return Map.of("status", "ok");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readyz() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "db_unavailable"));
        }
        return ResponseEntity.ok(Map.of("status", "ready"));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        // Public landing-page counters — polled by every visitor, cached briefly.
        return cache.cachedJson("stats:public", CacheService.TTL_STATS, this::computeStats);
    }

    private Map<String, Object> computeStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        Double kgSaved = jdbc.queryForObject(
            "SELECT COALESCE(SUM(" + EsgService.RESCUED_KG_SQL + "),0) FROM lots l WHERE "
            + EsgService.RESCUED_SQL, Double.class);
        Long deliveries = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tickets WHERE status = 'fulfilled'", Long.class);
        Long activeVolunteers = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT volunteer_id) FROM volunteer_routes "
            + "WHERE started_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'", Long.class);
        Double avgMinutes = jdbc.queryForObject(
            "SELECT AVG(EXTRACT(EPOCH FROM (finished_at - started_at)) / 60) FROM volunteer_routes "
            + "WHERE status = 'finished' AND finished_at IS NOT NULL", Double.class);
        Long totalLots = jdbc.queryForObject("SELECT COUNT(*) FROM lots", Long.class);
        Long expiredLots = jdbc.queryForObject("SELECT COUNT(*) FROM lots WHERE status = 'expired'", Long.class);
        double percentExpired = totalLots != null && totalLots > 0
            ? (expiredLots == null ? 0 : expiredLots) * 100.0 / totalLots : 0.0;

        out.put("kg_food_saved", kgSaved == null ? 0 : kgSaved);
        out.put("deliveries_completed", deliveries == null ? 0 : deliveries);
        out.put("active_volunteers", activeVolunteers == null ? 0 : activeVolunteers);
        out.put("avg_delivery_minutes", avgMinutes == null ? 0 : avgMinutes);
        out.put("percent_expired_lots", percentExpired);
        return out;
    }
}
