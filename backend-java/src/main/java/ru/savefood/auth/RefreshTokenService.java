package ru.savefood.auth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** Issues and atomically rotates opaque, server-side refresh credentials. */
@Service
public class RefreshTokenService {
    private static final Logger log = Logger.getLogger(RefreshTokenService.class.getName());
    public static final long REFRESH_TOKEN_LIFETIME_SECONDS = 30L * 24 * 60 * 60;
    private static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final long RETENTION_DAYS = 7;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    public RefreshTokenService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }
    /** Creates a new independently revocable session for a successfully authenticated user. */
    public String issue(int userId) {
        cleanupExpiredHistory();
        String token = randomToken();
        jdbc.update(
            "INSERT INTO refresh_sessions (session_id, user_id, token_hash, expires_at) "
                + "VALUES (?, ?, ?, NOW() + (? * INTERVAL '1 second'))",
            UUID.randomUUID(), userId, hash(token), REFRESH_TOKEN_LIFETIME_SECONDS);
        return token;
    }
    public Rotation rotate(String token) {
        if (!validToken(token)) {
            return null;
        }
        cleanupExpiredHistory();
        return tx.execute(status -> rotateLocked(token));
    }
    private Rotation rotateLocked(String token) {
        SessionIdentity session = sessionForToken(token);
        if (session == null || !lockUser(session.userId())) {
            return null;
        }
        lockSession(session.sessionId());
        String replacement = randomToken();
        List<Rotation> rotations = jdbc.query(
            """
            WITH eligible AS (
                SELECT rs.id, rs.session_id, rs.user_id
                FROM refresh_sessions rs
                JOIN users u ON u.id = rs.user_id
                WHERE rs.token_hash = ?
                  AND rs.consumed_at IS NULL
                  AND rs.revoked_at IS NULL
                  AND rs.expires_at > NOW()
                  AND NOT u.is_blocked
                FOR UPDATE OF rs
            ), consumed AS (
                UPDATE refresh_sessions rs
                SET consumed_at = NOW()
                FROM eligible e
                WHERE rs.id = e.id
                  AND rs.consumed_at IS NULL
                  AND rs.revoked_at IS NULL
                RETURNING rs.id, rs.session_id, rs.user_id
            ), inserted AS (
                INSERT INTO refresh_sessions
                    (session_id, user_id, token_hash, expires_at)
                SELECT session_id, user_id, ?, NOW() + (? * INTERVAL '1 second')
                FROM consumed
                RETURNING id, user_id
            )
            SELECT u.id, u.username, u.role, u.related_id
            FROM inserted
            JOIN users u ON u.id = inserted.user_id
            WHERE NOT u.is_blocked
            """,
            (rs, rowNum) -> new Rotation(
                replacement,
                rs.getInt("id"),
                rs.getString("username"),
                rs.getString("role"),
                rs.getObject("related_id") instanceof Number n ? n.intValue() : null),
            hash(token), hash(replacement), REFRESH_TOKEN_LIFETIME_SECONDS);
        return rotations.isEmpty() ? null : rotations.get(0);
    }
    /** Preserves the established 403 contract for a current token on a blocked account. */
    public boolean isActiveTokenForBlockedUser(String token) {
        if (!validToken(token)) {
            return false;
        }
        Boolean blocked = jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM refresh_sessions rs
                JOIN users u ON u.id = rs.user_id
                WHERE rs.token_hash = ?
                  AND rs.consumed_at IS NULL
                  AND rs.revoked_at IS NULL
                  AND rs.expires_at > NOW()
                  AND u.is_blocked
            )
            """,
            Boolean.class, hash(token));
        return Boolean.TRUE.equals(blocked);
    }
    /** Revokes every current or historical credential belonging to this session. */
    public void revokeSession(String token) {
        revokeSession(token, null);
    }
    /** Also removes this browser endpoint, but only when it belongs to the session's user. */
    public void revokeSession(String token, String pushEndpoint) {
        if (!validToken(token)) {
            return;
        }
        SessionIdentity revokedSession = tx.execute(status -> {
            SessionIdentity session = sessionForToken(token);
            if (session == null || !lockUser(session.userId())) {
                return null;
            }
            lockSession(session.sessionId());
            jdbc.update(
                """
                UPDATE refresh_sessions
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE session_id = ?
                """,
                session.sessionId());
            return session;
        });
        if (revokedSession != null && pushEndpoint != null && !pushEndpoint.isBlank()) {
            try {
                jdbc.update(
                    "DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ?",
                    revokedSession.userId(), pushEndpoint);
            } catch (RuntimeException e) {
                log.warning("[logout] refresh session revoked, but browser push cleanup failed");
            }
        }
    }
    private SessionIdentity sessionForToken(String token) {
        List<SessionIdentity> sessions = jdbc.query(
            "SELECT session_id, user_id FROM refresh_sessions WHERE token_hash = ?",
            (rs, rowNum) -> new SessionIdentity(
                rs.getObject("session_id", UUID.class), rs.getInt("user_id")),
            hash(token));
        return sessions.isEmpty() ? null : sessions.get(0);
    }
    private boolean lockUser(int userId) {
        return !jdbc.queryForList(
            "SELECT id FROM users WHERE id = ? FOR UPDATE", Integer.class, userId).isEmpty();
    }
    private void lockSession(UUID sessionId) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
            rs -> { }, sessionId);
    }
    private void cleanupExpiredHistory() {
        jdbc.update(
            """
            DELETE FROM refresh_sessions
            WHERE expires_at < NOW() - (? * INTERVAL '1 day')
               OR revoked_at < NOW() - (? * INTERVAL '1 day')
               OR consumed_at < NOW() - (? * INTERVAL '1 day')
            """,
            RETENTION_DAYS, RETENTION_DAYS, RETENTION_DAYS);
    }
    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static boolean validToken(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }
    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
    public record Rotation(
        String refreshToken,
        int userId,
        String username,
        String role,
        Integer relatedId
    ) {
    }
    private record SessionIdentity(UUID sessionId, int userId) {
    }
}
