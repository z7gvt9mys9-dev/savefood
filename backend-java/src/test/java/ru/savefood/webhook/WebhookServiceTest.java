package ru.savefood.webhook;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class WebhookServiceTest {
    private WebhookService service;
    @AfterEach
    void stopExecutor() {
        if (service != null) {
            service.stop();
        }
    }
    @Test
    void deliveryConcurrencyAndQueueAreBoundedForSlowEndpoints() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), anyInt())).thenAnswer(invocation ->
            List.of(hook(invocation.getArgument(1, Integer.class))));
        WebhookProperties limits = limits(2, 3, 1, 0);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        service = service(jdbc, limits, (url, secret, event, body) -> {
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            active.decrementAndGet();
            return 200;
        });
        for (int shopId = 1; shopId <= 6; shopId++) {
            service.fire(shopId, "lot.taken", Map.of("lot_id", shopId));
        }
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertEquals(2, maximumActive.get());
        assertTrue(service.queuedDeliveryCount() <= 3);
        assertTrue(service.createdThreadCount() <= 2);
        assertEquals(1, service.rejectedDeliveryCount());
        release.countDown();
    }
    @Test
    void queueFullIsRejectedImmediatelyAndDeterministically() throws Exception {
        JdbcTemplate jdbc = hooksJdbc(List.of(hook(1)));
        CountDownLatch release = new CountDownLatch(1);
        service = service(jdbc, limits(1, 1, 2, 0), (url, secret, event, body) -> {
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return 200;
        });
        service.fire(1, "lot.taken", Map.of());
        service.fire(2, "lot.taken", Map.of());
        service.fire(3, "lot.taken", Map.of());
        assertEquals(1, service.queuedDeliveryCount());
        assertEquals(1, service.rejectedDeliveryCount());
        assertEquals(1, service.createdThreadCount());
        release.countDown();
    }
    @Test
    void retriesAreBounded() throws Exception {
        JdbcTemplate jdbc = hooksJdbc(List.of(hook(1)));
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        service = service(jdbc, limits(1, 1, 1, 2), (url, secret, event, body) -> {
            attempts.incrementAndGet();
            if (attempts.get() == 3) {
                completed.countDown();
            }
            throw new java.io.IOException("unavailable");
        });
        service.fire(1, "lot.taken", Map.of());
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(3, attempts.get());
    }
    @Test
    void failingShopDoesNotBlockAnotherShopsSuccessfulWebhook() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), anyInt())).thenAnswer(invocation ->
            List.of(hook(invocation.getArgument(1, Integer.class))));
        CountDownLatch failingStarted = new CountDownLatch(1);
        CountDownLatch releaseFailing = new CountDownLatch(1);
        CountDownLatch successful = new CountDownLatch(1);
        service = service(jdbc, limits(2, 2, 1, 0), (url, secret, event, body) -> {
            if (url.endsWith("1")) {
                failingStarted.countDown();
                assertTrue(releaseFailing.await(2, TimeUnit.SECONDS));
                return 500;
            }
            successful.countDown();
            return 200;
        });
        service.fire(1, "lot.taken", Map.of());
        assertTrue(failingStarted.await(1, TimeUnit.SECONDS));
        service.fire(2, "lot.taken", Map.of());
        assertTrue(successful.await(1, TimeUnit.SECONDS));
        releaseFailing.countDown();
    }
    @Test
    void normalDeliveryUsesTheWebhookPayload() throws Exception {
        JdbcTemplate jdbc = hooksJdbc(List.of(hook(1)));
        CountDownLatch sent = new CountDownLatch(1);
        AtomicInteger status = new AtomicInteger();
        service = service(jdbc, limits(1, 1, 1, 0), (url, secret, event, body) -> {
            assertEquals("whsec_test", secret);
            assertEquals("lot.taken", event);
            assertTrue(new String(body).contains("\"lot_id\":42"));
            status.set(200);
            sent.countDown();
            return 200;
        });
        service.fire(1, "lot.taken", Map.of("lot_id", 42));
        assertTrue(sent.await(1, TimeUnit.SECONDS));
        assertEquals(200, status.get());
    }
    @Test
    void validationFailureLogsOnlySanitizedWebhookDestination() throws Exception {
        String url = "https://user:password@partner.example:8443/private/secret?token=query-secret#fragment-secret";
        CapturingHandler logs = captureWebhookLogs("https://partner.example:8443");
        try {
            service = new WebhookService(hooksJdbc(List.of(hook(1, url))), limits(1, 1, 1, 0),
                (sentUrl, secret, event, body) -> {
                    throw new AssertionError("unsafe webhook must not be delivered");
                }, ignored -> false);
            service.fire(1, "lot.taken", Map.of());
            assertTrue(logs.awaitWarning());
            assertSanitized(logs.messages(), "https://partner.example:8443", url);
        } finally {
            logs.close();
        }
    }
    @Test
    void retryFailureLogsSanitizedDestinationAndDeliversOriginalUrl() throws Exception {
        String url = "https://user:password@partner.example:8443/private/secret?token=query-secret#fragment-secret";
        CapturingHandler logs = captureWebhookLogs("https://partner.example:8443");
        CountDownLatch sent = new CountDownLatch(2);
        try {
            service = service(hooksJdbc(List.of(hook(1, url))), limits(1, 1, 1, 1),
                (sentUrl, secret, event, body) -> {
                    assertEquals(url, sentUrl);
                    sent.countDown();
                    throw new java.io.IOException("failed for " + url);
                });
            service.fire(1, "lot.taken", Map.of());
            assertTrue(sent.await(1, TimeUnit.SECONDS));
            assertTrue(logs.awaitWarning());
            assertSanitized(logs.messages(), "https://partner.example:8443", url);
        } finally {
            logs.close();
        }
    }
    private WebhookService service(JdbcTemplate jdbc, WebhookProperties limits,
                                   WebhookService.DeliverySender sender) {
        return new WebhookService(jdbc, limits, sender, ignored -> true);
    }
    private static JdbcTemplate hooksJdbc(List<Map<String, Object>> hooks) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), anyInt())).thenReturn(hooks);
        return jdbc;
    }
    private static Map<String, Object> hook(int id) {
        return hook(id, "https://partner.example/" + id);
    }
    private static Map<String, Object> hook(int id, String url) {
        return Map.of("id", id, "url", url,
            "secret", "whsec_test", "events", "*");
    }
    private static CapturingHandler captureWebhookLogs(String expectedDestination) {
        CapturingHandler handler = new CapturingHandler(expectedDestination);
        Logger.getLogger(WebhookService.class.getName()).addHandler(handler);
        return handler;
    }
    private static void assertSanitized(String message, String destination, String rawUrl) {
        assertTrue(message.contains(destination));
        assertTrue(!message.contains(rawUrl));
        assertTrue(!message.contains("query-secret"));
        assertTrue(!message.contains("fragment-secret"));
        assertTrue(!message.contains("user:password"));
        assertTrue(!message.contains("/private/secret"));
    }
    private static final class CapturingHandler extends Handler {
        private final CountDownLatch warning = new CountDownLatch(1);
        private final StringBuilder messages = new StringBuilder();
        private final String expectedDestination;
        CapturingHandler(String expectedDestination) {
            this.expectedDestination = expectedDestination;
        }
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()
                    && record.getMessage().contains(expectedDestination)) {
                synchronized (messages) {
                    messages.append(record.getMessage());
                }
                warning.countDown();
            }
        }
        @Override public void flush() { }
        @Override public void close() {
            Logger.getLogger(WebhookService.class.getName()).removeHandler(this);
        }
        boolean awaitWarning() throws InterruptedException {
            return warning.await(1, TimeUnit.SECONDS);
        }
        String messages() {
            synchronized (messages) {
                return messages.toString();
            }
        }
    }
    private static WebhookProperties limits(int workers, int queue, int perShop, int retries) {
        WebhookProperties limits = new WebhookProperties();
        limits.setWorkerCount(workers);
        limits.setQueueCapacity(queue);
        limits.setMaxInFlightPerShop(perShop);
        limits.setMaxRetries(retries);
        limits.setInitialBackoff(Duration.ofMillis(1));
        return limits;
    }
}
