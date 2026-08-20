package ru.savefood.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Owns the two-credential Telegram login protocol.
 *
 * <p>The initial token is intentionally status-only. Telegram confirmation creates
 * a separate random credential, stores only its hash, and returns the raw value to
 * the bot for delivery in the authenticated private chat. Completion consumption
 * is a single data-modifying PostgreSQL statement, so only one concurrent caller
 * can receive the user identity used to mint a JWT.
 */
@Service
public class TelegramLoginService {

    public static final int INITIAL_TTL_MINUTES = 10;
    public static final int COMPLETION_TTL_MINUTES = 5;

    private static final int TOKEN_BYTES = 24;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{32}");

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public TelegramLoginService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the status-only token returned to the initiating browser. */
    public String start() {
        String token = randomToken();
        jdbc.update(
            "INSERT INTO telegram_login_tokens (token, created_at) VALUES (?, NOW())",
            token);
        // Completion credentials live for less time than initial transactions, so
        // this generous cleanup horizon cannot remove a still-valid credential.
        jdbc.update(
            "DELETE FROM telegram_login_tokens "
                + "WHERE created_at < NOW() - (? * INTERVAL '1 minute')",
            INITIAL_TTL_MINUTES * 6);
        return token;
    }

    /** Returns only public transaction state; this method can never yield a user or JWT. */
    public String status(String initialToken) {
        if (!validToken(initialToken)) {
            return "expired";
        }
        List<String> states = jdbc.query(
            """
            SELECT CASE
                WHEN t.user_id IS NULL
                     AND t.completion_user_id IS NULL
                     AND t.created_at >= NOW() - (? * INTERVAL '1 minute')
                    THEN 'pending'
                WHEN t.user_id IS NULL
                     AND t.completion_user_id IS NOT NULL
                     AND t.completion_token_hash IS NOT NULL
                     AND t.completion_created_at >= NOW() - (? * INTERVAL '1 minute')
                     AND t.completion_delivered_at IS NULL
                    THEN 'pending'
                WHEN t.user_id IS NULL
                     AND t.completion_user_id IS NOT NULL
                     AND t.completion_token_hash IS NOT NULL
                     AND t.completion_created_at >= NOW() - (? * INTERVAL '1 minute')
                     AND t.completion_delivered_at IS NOT NULL
                     AND t.confirmed_chat_id IS NOT NULL
                     AND EXISTS (
                         SELECT 1
                         FROM users u
                         WHERE u.id = t.completion_user_id
                           AND NOT u.is_blocked
                           AND u.telegram_chat_id = t.confirmed_chat_id
                           AND NOT EXISTS (
                               SELECT 1 FROM users duplicate
                               WHERE duplicate.telegram_chat_id = t.confirmed_chat_id
                                 AND duplicate.id <> u.id
                           )
                     )
                    THEN 'confirmed'
                ELSE 'expired'
            END AS status
            FROM telegram_login_tokens t
            WHERE t.token = ?
            """,
            (rs, rowNum) -> rs.getString("status"),
            INITIAL_TTL_MINUTES, COMPLETION_TTL_MINUTES, COMPLETION_TTL_MINUTES, initialToken);
        return states.isEmpty() ? "expired" : states.get(0);
    }

    /**
     * Authenticates a pending transaction using one unique, unblocked Telegram link.
     * The raw completion credential is returned only to the bot caller and is never
     * persisted or logged.
     */
    public String confirm(String initialToken, String chatId) {
        if (!validToken(initialToken) || chatId == null || chatId.isBlank()) {
            return null;
        }
        String completionToken = randomToken();
        String completionHash = hash(completionToken);
        List<Integer> updated = jdbc.query(
            """
            UPDATE telegram_login_tokens t
            SET completion_user_id = u.id,
                confirmed_chat_id = ?,
                completion_token_hash = ?,
                completion_created_at = NOW()
            FROM users u
            WHERE t.token = ?
              AND t.user_id IS NULL
              AND t.completion_user_id IS NULL
              AND t.created_at >= NOW() - (? * INTERVAL '1 minute')
              AND u.telegram_chat_id = ?
              AND NOT u.is_blocked
              AND NOT EXISTS (
                  SELECT 1 FROM users duplicate
                  WHERE duplicate.telegram_chat_id = ?
                    AND duplicate.id <> u.id
              )
            RETURNING t.id
            """,
            (rs, rowNum) -> rs.getInt("id"),
            chatId, completionHash, initialToken, INITIAL_TTL_MINUTES, chatId, chatId);
        return updated.isEmpty() ? null : completionToken;
    }

    /** Revokes the exact confirmation if private Telegram delivery did not succeed. */
    public void revokeConfirmation(String initialToken, String completionToken) {
        if (!validToken(initialToken) || !validToken(completionToken)) {
            return;
        }
        jdbc.update(
            "DELETE FROM telegram_login_tokens WHERE token = ? AND completion_token_hash = ?",
            initialToken, hash(completionToken));
    }

    /** Makes the credential redeemable only after Telegram acknowledged private delivery. */
    public boolean markDelivered(String initialToken, String completionToken) {
        if (!validToken(initialToken) || !validToken(completionToken)) {
            return false;
        }
        int updated = jdbc.update(
            "UPDATE telegram_login_tokens SET completion_delivered_at = NOW() "
                + "WHERE token = ? AND completion_token_hash = ? "
                + "AND completion_delivered_at IS NULL "
                + "AND completion_created_at >= NOW() - (? * INTERVAL '1 minute')",
            initialToken, hash(completionToken), COMPLETION_TTL_MINUTES);
        return updated == 1;
    }

    /**
     * Atomically consumes a completion credential and returns a currently valid user.
     *
     * <p>The row is consumed even when it has expired or the account has since been
     * blocked/unlinked. A rejected credential therefore cannot become valid again.
     */
    public LoginUser complete(String completionToken) {
        if (!validToken(completionToken)) {
            return null;
        }
        List<LoginUser> users = jdbc.query(
            """
            WITH consumed AS (
                DELETE FROM telegram_login_tokens
                WHERE completion_token_hash = ?
                  AND completion_delivered_at IS NOT NULL
                RETURNING completion_user_id, confirmed_chat_id, completion_created_at
            )
            SELECT u.id, u.username, u.role, u.related_id
            FROM consumed c
            JOIN users u ON u.id = c.completion_user_id
            WHERE c.completion_created_at >= NOW() - (? * INTERVAL '1 minute')
              AND c.confirmed_chat_id IS NOT NULL
              AND NOT u.is_blocked
              AND u.telegram_chat_id = c.confirmed_chat_id
              AND NOT EXISTS (
                  SELECT 1 FROM users duplicate
                  WHERE duplicate.telegram_chat_id = c.confirmed_chat_id
                    AND duplicate.id <> u.id
              )
            """,
            (rs, rowNum) -> new LoginUser(
                rs.getInt("id"),
                rs.getString("username"),
                rs.getString("role"),
                rs.getObject("related_id") instanceof Number n ? n.intValue() : null),
            hash(completionToken), COMPLETION_TTL_MINUTES);
        return users.isEmpty() ? null : users.get(0);
    }

    /**
     * Returns whether a valid credential still exists after a failed consume.
     *
     * <p>This closes the small acknowledgement race where Telegram has already
     * displayed the private message but {@link #markDelivered} has not committed
     * yet. The caller may ask the browser to retry briefly without exposing any
     * account identity or changing the credential's one-time semantics.
     */
    public boolean completionMayActivate(String completionToken) {
        if (!validToken(completionToken)) {
            return false;
        }
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS ("
                + "SELECT 1 FROM telegram_login_tokens "
                + "WHERE completion_token_hash = ? "
                + "AND completion_created_at >= NOW() - (? * INTERVAL '1 minute'))",
            Boolean.class, hash(completionToken), COMPLETION_TTL_MINUTES);
        return Boolean.TRUE.equals(exists);
    }

    /** Idempotently revokes a pending or confirmed transaction by its initial token. */
    public void cancel(String initialToken) {
        if (validToken(initialToken)) {
            jdbc.update("DELETE FROM telegram_login_tokens WHERE token = ?", initialToken);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean validToken(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record LoginUser(int userId, String username, String role, Integer relatedId) {
    }
}
