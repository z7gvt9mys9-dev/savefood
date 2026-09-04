package ru.savefood.cache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
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
        return (T) cache.get(key, k -> producer.get());
    }
    /** Drop every entry. Used by tests; in production entries expire on their own. */
    public void clear() {
        caches.values().forEach(Cache::invalidateAll);
    }
}
