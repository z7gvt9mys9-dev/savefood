package ru.savefood.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CacheServiceTest {

    private CacheService cache;

    @BeforeEach
    void setUp() {
        cache = new CacheService();
    }

    @Test
    void producerRunsOncePerKeyWhileTheEntryIsAlive() {
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            assertThat(cache.<String>cachedJson("lots:1", CacheService.TTL_LOTS,
                () -> "value-" + calls.incrementAndGet())).isEqualTo("value-1");
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void differentKeysAreCachedIndependently() {
        assertThat(cache.<String>cachedJson("a", 30, () -> "A")).isEqualTo("A");
        assertThat(cache.<String>cachedJson("b", 30, () -> "B")).isEqualTo("B");
        assertThat(cache.<String>cachedJson("a", 30, () -> "changed")).isEqualTo("A");
    }

    /** Same key under two TTLs lives in two caches — they must not collide. */
    @Test
    void ttlBucketsAreSeparate() {
        assertThat(cache.<String>cachedJson("k", 10, () -> "short")).isEqualTo("short");
        assertThat(cache.<String>cachedJson("k", 60, () -> "long")).isEqualTo("long");
        assertThat(cache.<String>cachedJson("k", 10, () -> "ignored")).isEqualTo("short");
    }

    @Test
    void nonPositiveTtlBypassesTheCache() {
        AtomicInteger calls = new AtomicInteger();
        cache.cachedJson("k", 0, calls::incrementAndGet);
        cache.cachedJson("k", -1, calls::incrementAndGet);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void nullKeyBypassesTheCache() {
        AtomicInteger calls = new AtomicInteger();
        cache.cachedJson(null, 30, calls::incrementAndGet);
        cache.cachedJson(null, 30, calls::incrementAndGet);
        assertThat(calls.get()).isEqualTo(2);
    }

    /** A null result must not be remembered as a hit. */
    @Test
    void nullResultIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(cache.<String>cachedJson("k", 30, () -> {
            calls.incrementAndGet();
            return null;
        })).isNull();
        assertThat(cache.<String>cachedJson("k", 30, () -> {
            calls.incrementAndGet();
            return "now there is a value";
        })).isEqualTo("now there is a value");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void clearDropsEverything() {
        cache.cachedJson("k", 30, () -> "first");
        cache.clear();
        assertThat(cache.<String>cachedJson("k", 30, () -> "second")).isEqualTo("second");
    }
}
