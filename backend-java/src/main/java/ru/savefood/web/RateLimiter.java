package ru.savefood.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
    static final long DEFAULT_MAX_RETAINED_KEYS = 50_000;
    private static final long MINUTE_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final long HOUR_WINDOW_NANOS = TimeUnit.HOURS.toNanos(1);

    private record LimiterKey(String bucket, String client) {
    }

    private record Counter(int count, long expiresAtNanos) {
    }

    private final Cache<LimiterKey, Counter> counters;
    private final Ticker ticker;

    public RateLimiter() {
        this(DEFAULT_MAX_RETAINED_KEYS, Ticker.systemTicker());
    }

    @Autowired
    public RateLimiter(@Value("${savefood.rate-limiter-max-keys:50000}") long maxRetainedKeys) {
        this(maxRetainedKeys, Ticker.systemTicker());
    }

    RateLimiter(long maxRetainedKeys, Ticker ticker) {
        if (maxRetainedKeys <= 0) {
            throw new IllegalArgumentException("maxRetainedKeys must be positive");
        }
        this.ticker = ticker;
        this.counters = Caffeine.<LimiterKey, Counter>newBuilder()
            .maximumSize(maxRetainedKeys)
            .ticker(ticker)
            // Expiration is maintained by cache accesses; never create a scheduler thread.
            .scheduler(Scheduler.disabledScheduler())
            .expireAfter(new Expiry<LimiterKey, Counter>() {
                @Override
                public long expireAfterCreate(LimiterKey key, Counter counter, long currentTime) {
                    return remainingNanos(counter, currentTime);
                }

                @Override
                public long expireAfterUpdate(LimiterKey key, Counter counter, long currentTime,
                                              long currentDuration) {
                    return remainingNanos(counter, currentTime);
                }

                @Override
                public long expireAfterRead(LimiterKey key, Counter counter, long currentTime,
                                            long currentDuration) {
                    return currentDuration;
                }
            })
            .build();
    }

    /** @param perMinute requests allowed per 60s window for this key+client. */
    public void check(String bucket, String client, int perMinute) {
        check(bucket, client, perMinute, MINUTE_WINDOW_NANOS, "1 minute");
    }

    /** Per-hour window variant — the analogue of slowapi {@code @limiter.limit("N/hour")}. */
    public void checkHourly(String bucket, String client, int perHour) {
        check(bucket, client, perHour, HOUR_WINDOW_NANOS, "1 hour");
    }

    private void check(String bucket, String client, int limit, long windowNanos, String windowLabel) {
        LimiterKey key = new LimiterKey(bucket, client == null ? "?" : client);
        long now = ticker.read();
        Counter updated = counters.asMap().compute(key, (unused, current) -> {
            if (current == null || now >= current.expiresAtNanos()) {
                return new Counter(1, now + windowNanos);
            }
            return new Counter(current.count() + 1, current.expiresAtNanos());
        });
        if (updated.count() > limit) {
            throw new ApiException(429, "Rate limit exceeded: " + limit + " per " + windowLabel);
        }
    }

    long retainedKeyCount() {
        counters.cleanUp();
        return counters.estimatedSize();
    }

    private static long remainingNanos(Counter counter, long now) {
        return Math.max(0, counter.expiresAtNanos() - now);
    }
}
