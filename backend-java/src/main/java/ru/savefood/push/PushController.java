package ru.savefood.push;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.savefood.push.dto.FcmRegisterIn;
import ru.savefood.push.dto.FcmUnregisterIn;
import ru.savefood.push.dto.SubscriptionIn;
import ru.savefood.push.dto.UnsubscribeIn;
import ru.savefood.security.Auth;
import ru.savefood.security.CurrentUser;
import ru.savefood.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Java port of backend/push_routes.py — subscribe/unsubscribe for Web Push
 * (VAPID) and FCM (native Android). The stored subscriptions are read by the
 * Python notifier, which keeps the actual push fan-out during the migration; this
 * service owns only the storage + the VAPID public-key probe.
 */
@RestController
@RequestMapping("/push")
public class PushController {

    private final JdbcTemplate jdbc;
    private final PushService push;

    public PushController(JdbcTemplate jdbc, PushService push) {
        this.jdbc = jdbc;
        this.push = push;
    }

    @GetMapping("/public_key")
    public Map<String, Object> publicKey() {
        if (!push.isConfigured()) {
            throw new ApiException(503, "Web Push не настроен (нет VAPID-ключей)");
        }
        return Map.of("key", push.vapidPublicKey());
    }

    @PostMapping("/subscribe")
    public Map<String, Object> subscribe(@RequestBody SubscriptionIn payload, @Auth CurrentUser user) {
        if (!push.isConfigured()) {
            throw new ApiException(503, "Web Push не настроен");
        }
        String p256dh = payload.keys() == null ? null : payload.keys().get("p256dh");
        String auth = payload.keys() == null ? null : payload.keys().get("auth");
        if (isBlank(payload.endpoint()) || isBlank(p256dh) || isBlank(auth)) {
            throw new ApiException(400, "Неполная подписка");
        }
        push.saveSubscription(userId(user), payload.endpoint(), p256dh, auth);
        return Map.of("ok", true);
    }

    @PostMapping("/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestBody UnsubscribeIn payload, @Auth CurrentUser user) {
        push.deleteSubscription(userId(user), payload.endpoint());
        return Map.of("ok", true);
    }

    @PostMapping("/fcm/register")
    public Map<String, Object> fcmRegister(@RequestBody FcmRegisterIn payload, @Auth CurrentUser user) {
        if (isBlank(payload.token())) {
            throw new ApiException(400, "Пустой FCM-токен");
        }
        Map<String, Object> me = userIdentity(user);
        // The (role, related_id) the client sends must match the authenticated
        // account, else a user could route another account's pushes to their device.
        Integer myRelated = me.get("related_id") == null ? null : ((Number) me.get("related_id")).intValue();
        if (!Objects.equals(payload.role(), me.get("role")) || !Objects.equals(payload.relatedId(), myRelated)) {
            throw new ApiException(403, "role/related_id не совпадают с аккаунтом");
        }
        push.saveFcmToken(((Number) me.get("id")).intValue(), payload.token(), (String) me.get("role"), myRelated);
        return Map.of("ok", true);
    }

    @PostMapping("/fcm/unregister")
    public Map<String, Object> fcmUnregister(@RequestBody FcmUnregisterIn payload, @Auth CurrentUser user) {
        push.deleteFcmToken(userId(user), payload.token());
        return Map.of("ok", true);
    }

    // ── helpers (push_routes.py _user_id / _user_identity) ───────────────────────

    private int userId(CurrentUser user) {
        return ((Number) userIdentity(user).get("id")).intValue();
    }

    private Map<String, Object> userIdentity(CurrentUser user) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, role, related_id FROM users WHERE username = ?", user.sub());
        if (rows.isEmpty()) {
            throw new ApiException(404, "User not found");
        }
        return rows.get(0);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
