package kz.savefood.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Port of backend/database.py {@code log_action}: appends an admin action to
 * {@code audit_log}. Failures are swallowed (best-effort) so an audit write can
 * never break the request that triggered it.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String actorUsername, String action, String targetType,
                    Integer targetId, String details) {
        try {
            jdbc.update(
                "INSERT INTO audit_log (actor_username, action, target_type, target_id, details) "
                + "VALUES (?, ?, ?, ?, ?)",
                actorUsername, action, targetType, targetId, details);
        } catch (RuntimeException e) {
            log.warn("audit log_action failed (swallowed): {}", e.getMessage());
        }
    }
}
