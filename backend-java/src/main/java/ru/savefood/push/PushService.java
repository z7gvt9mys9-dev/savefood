package ru.savefood.push;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class PushService {
    private final JdbcTemplate jdbc;
    private final String vapidPublicKey;
    private final String vapidPrivateKey;
    public PushService(JdbcTemplate jdbc,
                       @Value("${savefood.push.vapid-public-key:}") String vapidPublicKey,
                       @Value("${savefood.push.vapid-private-key:}") String vapidPrivateKey) {
        this.jdbc = jdbc;
        this.vapidPublicKey = vapidPublicKey;
        this.vapidPrivateKey = vapidPrivateKey;
    }
    public boolean isConfigured() {
        return !vapidPublicKey.isEmpty() && !vapidPrivateKey.isEmpty();
    }
    public String vapidPublicKey() {
        return vapidPublicKey;
    }
    public void saveSubscription(int userId, String endpoint, String p256dh, String auth) {
        jdbc.update(
            "INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (endpoint) DO UPDATE SET user_id = EXCLUDED.user_id, "
            + "p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth",
            userId, endpoint, p256dh, auth);
    }
    public void deleteSubscription(int userId, String endpoint) {
        jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ?", userId, endpoint);
    }
    public void saveFcmToken(int userId, String token, String role, Integer relatedId) {
        jdbc.update(
            "INSERT INTO fcm_tokens (user_id, token, role, related_id) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (token) DO UPDATE SET user_id = EXCLUDED.user_id, "
            + "role = EXCLUDED.role, related_id = EXCLUDED.related_id",
            userId, token, role, relatedId);
    }
    public void deleteFcmToken(int userId, String token) {
        jdbc.update("DELETE FROM fcm_tokens WHERE user_id = ? AND token = ?", userId, token);
    }
}
