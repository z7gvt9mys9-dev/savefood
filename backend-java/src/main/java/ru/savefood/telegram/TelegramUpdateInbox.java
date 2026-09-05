package ru.savefood.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable acceptance and bounded recovery. Never delete processed IDs: they are deduplication keys. */
@Repository
public class TelegramUpdateInbox {
    public static final int MAX_PAYLOAD_BYTES = 65536;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int maxAttempts;
    private final int backoffSeconds;
    private final int claimSeconds;

    public TelegramUpdateInbox(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${savefood.telegram.inbox-max-attempts:5}") int maxAttempts,
            @Value("${savefood.telegram.inbox-backoff-seconds:30}") int backoffSeconds,
            @Value("${savefood.telegram.inbox-claim-seconds:120}") int claimSeconds) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxAttempts = Math.max(1, Math.min(20, maxAttempts));
        this.backoffSeconds = Math.max(1, Math.min(3600, backoffSeconds));
        this.claimSeconds = Math.max(1, Math.min(3600, claimSeconds));
    }

    public void accept(long updateId, JsonNode payload) {
        // Return only after commit, even if called with an ambient transaction.
        tx.executeWithoutResult(ignored -> jdbc.update(
            "INSERT INTO telegram_update_inbox (update_id, payload) VALUES (?, ?) "
                + "ON CONFLICT (update_id) DO NOTHING", updateId, payload.toString()));
    }

    public Claim claim() {
        return tx.execute(ignored -> {
            // Exhausted/crashed claims become terminal in bounded batches, without waiting on live workers.
            jdbc.update("UPDATE telegram_update_inbox SET status = 'failed', payload = '{}', "
                + "last_error = 'attempt limit reached' WHERE update_id IN ("
                + "SELECT update_id FROM telegram_update_inbox WHERE status IN ('pending', 'processing') "
                + "AND attempts >= ? AND next_attempt_at <= clock_timestamp() "
                + "ORDER BY next_attempt_at, update_id LIMIT 100 FOR UPDATE SKIP LOCKED)", maxAttempts);
            List<Claim> rows = jdbc.query(
                "UPDATE telegram_update_inbox SET status = 'processing', attempts = attempts + 1, "
                    + "claimed_at = clock_timestamp(), next_attempt_at = clock_timestamp() + (? * INTERVAL '1 second') "
                    + "WHERE update_id = (SELECT update_id FROM telegram_update_inbox "
                    + "WHERE status IN ('pending', 'processing') AND attempts < ? "
                    + "AND next_attempt_at <= clock_timestamp() ORDER BY next_attempt_at, update_id "
                    + "LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING update_id, attempts",
                (rs, n) -> new Claim(rs.getLong("update_id"), rs.getInt("attempts")),
                claimSeconds, maxAttempts);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    public void process(Claim claim, Consumer<String> handler) {
        try {
            tx.executeWithoutResult(ignored -> {
                List<String> payloads = jdbc.query(
                    "SELECT payload FROM telegram_update_inbox WHERE update_id = ? "
                        + "AND status = 'processing' AND attempts = ? FOR UPDATE SKIP LOCKED",
                    (rs, n) -> rs.getString("payload"), claim.updateId(), claim.attempt());
                if (payloads.isEmpty()) return;
                // ChatService's REQUIRED transaction joins this transaction. Its ticket lock and
                // authorization remain intact. A crash rolls back BOTH its rows and this completion.
                // Keep the inbox lock throughout external I/O: expiry cannot steal a live worker's row.
                handler.accept(payloads.get(0));
                jdbc.update("UPDATE telegram_update_inbox SET status = 'processed', "
                    + "processed_at = clock_timestamp(), last_error = NULL, payload = '{}' WHERE update_id = ?",
                    claim.updateId());
            });
        } catch (RuntimeException failure) {
            tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE telegram_update_inbox SET status = ?, last_error = ?, "
                    + "payload = CASE WHEN ? THEN '{}' ELSE payload END, "
                    + "next_attempt_at = clock_timestamp() + (? * INTERVAL '1 second') "
                    + "WHERE update_id = ? AND attempts = ? AND status = 'processing'",
                claim.attempt() >= maxAttempts ? "failed" : "pending",
                failure.getClass().getSimpleName(), claim.attempt() >= maxAttempts,
                Math.min(3600L, (long) backoffSeconds * (1L << (claim.attempt() - 1))),
                claim.updateId(), claim.attempt()));
        }
    }

    public record Claim(long updateId, int attempt) { }
}
