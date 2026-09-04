package ru.savefood.web;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
@Component
public class RateLimiter {
    private static final long WINDOW_MS = 60_000;
    private record Counter(long windowStart, int count) {
    }
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    /** @param perMinute requests allowed per 60s window for this key+client. */
    public void check(String bucket, String client, int perMinute) {
        check(bucket, client, perMinute, WINDOW_MS, "1 minute");
    }
    /** Per-hour window variant — the analogue of slowapi {@code @limiter.limit("N/hour")}. */
    public void checkHourly(String bucket, String client, int perHour) {
        check(bucket, client, perHour, 3_600_000L, "1 hour");
    }
    private void check(String bucket, String client, int limit, long windowMs, String windowLabel) {
        String key = bucket + '|' + (client == null ? "?" : client);
        long now = System.currentTimeMillis();
        Counter updated = counters.compute(key, (k, cur) -> {
            if (cur == null || now - cur.windowStart() >= windowMs) {
                return new Counter(now, 1);
            }
            return new Counter(cur.windowStart(), cur.count() + 1);
        });
        if (updated.count() > limit) {
            throw new ApiException(429, "Rate limit exceeded: " + limit + " per " + windowLabel);
        }
    }
}
