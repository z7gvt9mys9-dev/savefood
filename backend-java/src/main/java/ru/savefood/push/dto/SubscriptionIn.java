package ru.savefood.push.dto;

import java.util.Map;

/** Port of push_routes.py {@code SubscriptionIn} — the browser PushSubscription shape. */
public record SubscriptionIn(String endpoint, Map<String, String> keys) {
}
