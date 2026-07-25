package ru.savefood.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Read-through cache for the hottest reads — the public active-lots map every
 * recipient loads, the platform stats and the Impact widgets.
 *
 * <p>Backed by an in-process Caffeine cache rather than Redis. At this scale a
 * second network hop and another container buy nothing: the cached payloads are
 * small, the TTLs are seconds, and Postgres stays the source of truth either way.
 * The trade-off has one consequence worth knowing — with several backend
 * instances each keeps its own copy, so a value can be a TTL stale on one and
 * fresh on another. For a lots map that turns over every 10 seconds that is
 * invisible; if a deployment ever needs a shared cache, replace the body of
 * {@link #cachedJson} and every caller stays untouched.
 *
 * <p>Caffeine's expiry is a per-cache setting rather than a per-entry one, so one
 * cache instance is kept per distinct TTL.
 */
@Service
public class CacheService {

    /** Active-lots map: refreshed often, tolerates a few seconds of staleness. */
    public static final int TTL_LOTS = 10;
    /** Platform stats / Impact aggregates: heavier queries, coarser freshness. */
    public static final int TTL_STATS = 15;
    /** Volunteer live location reads. */
    public static final int TTL_LOCATION = 60;

    private static final int MAX_ENTRIES_PER_TTL = 512;

    private final ConcurrentMap<Integer, Cache<String, Object>> caches = new ConcurrentHashMap<>();

    /**
     * Return the cached value for {@code key}, or produce, store and return it.
     *
     * <p>A {@code ttlSeconds} of zero or less bypasses the cache, which keeps the
     * old "always fresh" behaviour reachable without a second code path.
     */
    @SuppressWarnings("unchecked")
    public <T> T cachedJson(String key, int ttlSeconds, Supplier<T> producer) {
        if (key == null || ttlSeconds <= 0) {
            return producer.get();
        }
        Cache<String, Object> cache = caches.computeIfAbsent(ttlSeconds, ttl ->
            Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttl))
                .maximumSize(MAX_ENTRIES_PER_TTL)
                .build());
        // Caffeine treats a null load as "no value", so a producer returning null
        // is never cached as a hit — which is the behaviour we want here.
        return (T) cache.get(key, k -> producer.get());
    }

    /** Drop every entry. Used by tests; in production entries expire on their own. */
    public void clear() {
        caches.values().forEach(Cache::invalidateAll);
    }
}
