package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.savefood.needy.NeedyRepository;
/** Focused coverage for recipient data-export ownership and secret redaction. */
class NeedyDataExportIT extends PostgresIT {
    private NeedyRepository repo;
    private int needyId;
    private int userId;
    @BeforeEach
    void createRecipient() {
        repo = new NeedyRepository(jdbc);
        needyId = insertNeedy("Export recipient");
        userId = insertUser(needyId, "recipient-export", "password-hash-should-not-export");
    }
    @Test
    void exportIncludesOnlyTheRecipientsSafeAccountDeviceAndSessionMetadata() throws Exception {
        jdbc.update("UPDATE users SET telegram_chat_id = ?, google_id = ?, yandex_id = ?, is_blocked = TRUE "
                + "WHERE id = ?", "tg-123", "google-123", "yandex-123", userId);
        jdbc.update("INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?, ?, ?)",
            userId, "https://push.example/device-secret", "push-public-key", "push-auth-secret");
        jdbc.update("INSERT INTO fcm_tokens (user_id, token, role, related_id) VALUES (?, ?, 'needy', ?)",
            userId, "fcm-registration-secret", needyId);
        jdbc.update("INSERT INTO refresh_sessions "
                + "(session_id, user_id, token_hash, expires_at, consumed_at, revoked_at) "
                + "VALUES ('11111111-1111-1111-1111-111111111111', ?, decode('deadbeef', 'hex'), "
                + "NOW() + INTERVAL '1 day', NOW(), NULL)", userId);
        Map<String, Object> export = repo.exportAccount(needyId);
        assertThat(export.keySet()).contains("account", "account_links", "push_subscriptions",
            "fcm_registrations", "refresh_sessions", "profile", "tickets", "ratings",
            "notifications", "messages");
        assertThat(map(export, "account")).containsEntry("user_id", userId)
            .containsEntry("username", "recipient-export").containsEntry("is_blocked", true)
            .containsKey("account_created_at").doesNotContainKey("hashed_password");
        assertThat(map(export, "account_links")).containsEntry("telegram_chat_id", "tg-123")
            .containsEntry("google_id", "google-123").containsEntry("yandex_id", "yandex-123");
        assertThat(rows(export, "push_subscriptions")).singleElement().satisfies(subscription ->
            assertThat(subscription).containsEntry("type", "web_push").containsKey("created_at")
                .doesNotContainKeys("endpoint", "p256dh", "auth"));
        assertThat(rows(export, "fcm_registrations")).singleElement().satisfies(registration ->
            assertThat(registration).containsEntry("type", "fcm").containsKey("created_at")
                .doesNotContainKey("token"));
        assertThat(rows(export, "refresh_sessions")).singleElement().satisfies(session ->
            assertThat(session).containsEntry("status", "consumed").containsKeys("session_id", "created_at",
                "expires_at", "consumed_at", "revoked_at").doesNotContainKey("token_hash"));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(export);
        assertThat(json).doesNotContain("password-hash-should-not-export", "push-public-key",
            "push-auth-secret", "device-secret", "fcm-registration-secret", "deadbeef", "kyc");
    }
    @Test
    void exportUsesOnlyTheUserLinkedToTheRequestedRecipientAndHandlesAbsentOptionalData() {
        int otherNeedy = insertNeedy("Other recipient");
        int otherUser = insertUser(otherNeedy, "other-recipient", "other-password-hash");
        jdbc.update("UPDATE users SET telegram_chat_id = ?, google_id = ?, yandex_id = ? WHERE id = ?",
            "other-tg", "other-google", "other-yandex", otherUser);
        jdbc.update("INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?, ?, ?)",
            otherUser, "https://push.example/other-device", "other-key", "other-auth");
        jdbc.update("INSERT INTO fcm_tokens (user_id, token, role, related_id) VALUES (?, ?, 'needy', ?)",
            otherUser, "other-fcm", otherNeedy);
        jdbc.update("INSERT INTO refresh_sessions (session_id, user_id, token_hash, expires_at) "
                + "VALUES ('22222222-2222-2222-2222-222222222222', ?, decode('0102', 'hex'), "
                + "NOW() + INTERVAL '1 day')", otherUser);
        Map<String, Object> export = repo.exportAccount(needyId);
        assertThat(map(export, "account")).containsEntry("username", "recipient-export")
            .doesNotContainValue("other-recipient");
        assertThat(map(export, "account_links")).containsEntry("telegram_chat_id", null)
            .containsEntry("google_id", null).containsEntry("yandex_id", null);
        assertThat(rows(export, "push_subscriptions")).isEmpty();
        assertThat(rows(export, "fcm_registrations")).isEmpty();
        assertThat(rows(export, "refresh_sessions")).isEmpty();
    }
    private int insertUser(int relatedId, String username, String passwordHash) {
        return jdbc.queryForObject(
            "INSERT INTO users (username, hashed_password, role, related_id) VALUES (?, ?, 'needy', ?) RETURNING id",
            Integer.class, username, passwordHash, relatedId);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> export, String section) {
        return (Map<String, Object>) export.get(section);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> export, String section) {
        return (List<Map<String, Object>>) export.get(section);
    }
}
