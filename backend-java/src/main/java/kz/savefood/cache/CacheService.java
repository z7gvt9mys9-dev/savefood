package kz.savefood.cache;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Read-through cache facade, ported from cache.py — the short-TTL layer over the
 * hottest reads (the active-lots map). The public API mirrors
 * {@code cache.cached_json(key, ttl, producer)} so callers are identical to the
 * Python routes.
 *
 * <p>Like the Telegram/Web-Push fan-out, the actual Redis backend stays with the
 * Python layer during the migration: cache.py is already a transparent no-op
 * whenever {@code REDIS_URL} is unset (its documented default), and any Redis
 * error degrades to a cache miss. This port preserves exactly that contract —
 * every call produces a fresh value (a guaranteed miss), so it is always correct,
 * never serves staleness, and adds no Redis client dependency. A Redis-backed
 * implementation can drop in behind this same method when the module is cut over.
 */
@Service
public class CacheService {

    /** TTL constants kept for parity with cache.py; honoured once a backend is wired. */
    public static final int TTL_LOTS = 10;
    public static final int TTL_STATS = 15;
    public static final int TTL_LOCATION = 60;

    /** Read-through: with no cache backend this always produces a fresh value. */
    public <T> T cachedJson(String key, int ttlSeconds, Supplier<T> producer) {
        return producer.get();
    }
}
