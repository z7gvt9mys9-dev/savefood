package ru.savefood.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    @Test
    void allowsRequestsBelowTheConfiguredLimit() {
        RateLimiter limiter = limiter(10);

        for (int request = 0; request < 3; request++) {
            limiter.check("register", "192.0.2.1", 3);
        }
    }

    @Test
    void rejectsRequestsAboveTheConfiguredLimit() {
        RateLimiter limiter = limiter(10);
        limiter.check("register", "192.0.2.1", 2);
        limiter.check("register", "192.0.2.1", 2);

        assertThatThrownBy(() -> limiter.check("register", "192.0.2.1", 2))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("2 per 1 minute");
    }

    @Test
    void expiredKeyIsEvictedAndCanUseANewWindow() {
        TestTicker ticker = new TestTicker();
        RateLimiter limiter = new RateLimiter(10, ticker);
        limiter.check("register", "192.0.2.1", 1);
        assertThatThrownBy(() -> limiter.check("register", "192.0.2.1", 1))
            .isInstanceOf(ApiException.class);

        ticker.advance(1, TimeUnit.MINUTES);

        assertThat(limiter.retainedKeyCount()).isZero();
        limiter.check("register", "192.0.2.1", 1);
    }

    @Test
    void retainedKeysStayWithinConfiguredMaximumDuringUniqueKeyBurst() {
        RateLimiter limiter = limiter(128);

        for (int request = 0; request < 5_000; request++) {
            limiter.check("register", "198.51.100." + request, 1);
        }

        assertThat(limiter.retainedKeyCount()).isLessThanOrEqualTo(128);
    }

    @Test
    void ipv6StyleHighCardinalityKeysStayBounded() {
        RateLimiter limiter = limiter(64);

        for (int request = 0; request < 3_000; request++) {
            limiter.check("register", "2001:db8:abcd::" + Integer.toHexString(request), 1);
        }

        assertThat(limiter.retainedKeyCount()).isLessThanOrEqualTo(64);
    }

    @Test
    void concurrentRequestsCannotBypassLimit() throws Exception {
        RateLimiter limiter = limiter(100);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int request = 0; request < 100; request++) {
                requests.add(() -> {
                    try {
                        limiter.check("register", "192.0.2.1", 10);
                        return true;
                    } catch (ApiException rejected) {
                        return false;
                    }
                });
            }

            long accepted = executor.invokeAll(requests).stream()
                .filter(result -> {
                    try {
                        return result.get();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                })
                .count();

            assertThat(accepted).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void bucketsRemainIndependent() {
        RateLimiter limiter = limiter(10);
        limiter.check("register", "192.0.2.1|login", 1);
        limiter.check("register|192.0.2.1", "login", 1);

        assertThatThrownBy(() -> limiter.check("register", "192.0.2.1|login", 1))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void expiringOneKeyDoesNotCorruptAnother() {
        TestTicker ticker = new TestTicker();
        RateLimiter limiter = new RateLimiter(10, ticker);
        limiter.check("register", "first", 2);
        ticker.advance(30, TimeUnit.SECONDS);
        limiter.check("register", "second", 2);
        ticker.advance(31, TimeUnit.SECONDS);

        assertThat(limiter.retainedKeyCount()).isEqualTo(1);
        limiter.check("register", "second", 2);
        assertThatThrownBy(() -> limiter.check("register", "second", 2))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void doesNotCreateRateLimiterBackgroundThreads() {
        long before = caffeineThreadCount();
        RateLimiter limiter = limiter(10);
        limiter.check("register", "192.0.2.1", 1);
        limiter.retainedKeyCount();

        assertThat(caffeineThreadCount()).isEqualTo(before);
    }

    private static RateLimiter limiter(long maximumKeys) {
        return new RateLimiter(maximumKeys, new TestTicker());
    }

    private static long caffeineThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().contains("Caffeine"))
            .count();
    }

    private static final class TestTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(long duration, TimeUnit unit) {
            nanos.addAndGet(unit.toNanos(duration));
        }
    }
}
