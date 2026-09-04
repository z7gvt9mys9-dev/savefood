package ru.savefood.webhook;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class WebhookService {
    private static final Logger log = Logger.getLogger(WebhookService.class.getName());
    /** The events a partner webhook may subscribe to (webhook_service.py {@code EVENTS}). */
    public static final java.util.List<String> EVENTS =
        java.util.List.of("lot.taken", "lot.confirmed", "receipt.parsed");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebhookProperties properties;
    private final ThreadPoolExecutor delivery;
    private final HttpClient http;
    private final DeliverySender sender;
    private final UrlValidator urlValidator;
    private final ConcurrentHashMap<Integer, Semaphore> shopPermits = new ConcurrentHashMap<>();
    private final AtomicInteger createdThreads = new AtomicInteger();
    private final AtomicLong rejectedDeliveries = new AtomicLong();
    public WebhookService(JdbcTemplate jdbc, WebhookProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        properties.validate();
        this.http = HttpClient.newBuilder().connectTimeout(properties.getRequestTimeout()).build();
        this.sender = this::sendHttp;
        this.urlValidator = WebhookService::isSafeWebhookUrl;
        this.delivery = newExecutor();
    }
    WebhookService(JdbcTemplate jdbc, WebhookProperties properties, DeliverySender sender,
                   UrlValidator urlValidator) {
        this.jdbc = jdbc;
        this.properties = properties;
        properties.validate();
        this.http = HttpClient.newBuilder().connectTimeout(properties.getRequestTimeout()).build();
        this.sender = sender;
        this.urlValidator = urlValidator;
        this.delivery = newExecutor();
    }
    private ThreadPoolExecutor newExecutor() {
        return new ThreadPoolExecutor(properties.getWorkerCount(), properties.getWorkerCount(),
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.getQueueCapacity()), r -> {
                Thread t = new Thread(r, "webhook-delivery-" + createdThreads.incrementAndGet());
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    }
    /** Fan {@code event} out to every matching active webhook of the shop. */
    public void fire(int shopId, String event, Map<String, Object> data) {
        List<Map<String, Object>> hooks;
        try {
            hooks = jdbc.queryForList(
                "SELECT id, url, secret, events FROM webhooks WHERE shop_id = ? AND active", shopId);
        } catch (RuntimeException e) {
            log.warning("[webhook] fire(" + shopId + ", " + event + ") lookup failed: " + e);
            return;
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map<String, Object> h : hooks) {
            if (eventMatches((String) h.get("events"), event)) {
                targets.add(h);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        byte[] body;
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("event", event);
            envelope.put("created_at", OffsetDateTime.now().toString());
            envelope.put("data", data);
            body = mapper.writeValueAsBytes(envelope);
        } catch (Exception e) {
            log.warning("[webhook] serialize failed: " + e);
            return;
        }
        for (Map<String, Object> h : targets) {
            int id = ((Number) h.get("id")).intValue();
            String url = (String) h.get("url");
            String secret = (String) h.get("secret");
            Semaphore permits = shopPermits.computeIfAbsent(shopId,
                ignored -> new Semaphore(properties.getMaxInFlightPerShop()));
            if (!permits.tryAcquire()) {
                rejectedDeliveries.incrementAndGet();
                log.warning("[webhook] delivery for webhook id=" + id
                    + " dropped: per-shop delivery limit reached");
                continue;
            }
            try {
                delivery.execute(() -> {
                    try {
                        deliverWithRetries(id, url, secret, event, body);
                    } finally {
                        permits.release();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                permits.release();
                rejectedDeliveries.incrementAndGet();
                log.warning("[webhook] delivery for webhook id=" + id
                    + " dropped: delivery queue is full");
            }
        }
    }
    static boolean eventMatches(String eventsCsv, String event) {
        String csv = eventsCsv == null || eventsCsv.isBlank() ? "*" : eventsCsv;
        for (String e : csv.split(",")) {
            String s = e.strip();
            if (s.equals("*") || s.equals(event)) {
                return true;
            }
        }
        return false;
    }
    static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return "sha256=" + hex;
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
    private void deliverWithRetries(int webhookId, String url, String secret, String event, byte[] body) {
        Integer status = null;
        String destination = sanitizeWebhookUrlForLog(url);
        if (!urlValidator.isSafe(url)) {
            log.warning("[webhook] delivery for webhook id=" + webhookId
                + " to " + destination + " blocked: resolves to an internal/unsafe address");
        } else {
            for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
                try {
                    status = sender.send(url, secret, event, body);
                    if (!isRetryableStatus(status) || attempt == properties.getMaxRetries()) {
                        break;
                    }
                } catch (Exception e) {
                    if (attempt == properties.getMaxRetries()) {
                        log.warning("[webhook] delivery for webhook id=" + webhookId
                            + " to " + destination + " failed after " + (attempt + 1)
                            + " attempts: " + e.getClass().getSimpleName());
                        break;
                    }
                }
                if (!backoff(attempt)) {
                    break;
                }
            }
        }
        try {
            jdbc.update("UPDATE webhooks SET last_status = ?, last_delivery_at = ? WHERE id = ?",
                status, OffsetDateTime.now(), webhookId);
        } catch (RuntimeException ignored) {
        }
    }
    private Integer sendHttp(String url, String secret, String event, byte[] body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(properties.getRequestTimeout())
            .header("content-type", "application/json")
            .header("x-savefood-event", event)
            .header("x-savefood-signature", sign(secret, body))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        return resp.statusCode();
    }
    private boolean backoff(int attempt) {
        try {
            long delay = Math.multiplyExact(properties.getInitialBackoff().toMillis(), 1L << attempt);
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ArithmeticException e) {
            return false;
        }
    }
    private static boolean isRetryableStatus(Integer status) {
        return status != null && (status == 408 || status == 429 || status >= 500);
    }
    static String sanitizeWebhookUrlForLog(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) {
                return "[invalid webhook URL]";
            }
            int port = uri.getPort();
            return scheme + "://" + host + (port == -1 ? "" : ":" + port);
        } catch (RuntimeException e) {
            return "[invalid webhook URL]";
        }
    }
    @PreDestroy
    void stop() {
        delivery.shutdown();
    }
    int activeDeliveryCount() { return delivery.getActiveCount(); }
    int queuedDeliveryCount() { return delivery.getQueue().size(); }
    int createdThreadCount() { return createdThreads.get(); }
    long rejectedDeliveryCount() { return rejectedDeliveries.get(); }
    @FunctionalInterface
    interface DeliverySender {
        Integer send(String url, String secret, String event, byte[] body) throws Exception;
    }
    @FunctionalInterface
    interface UrlValidator {
        boolean isSafe(String url);
    }
    static boolean isSafeWebhookUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return false;
        }
        for (InetAddress ip : addrs) {
            if (isBlockedAddress(ip)) {
                return false;
            }
        }
        return true;
    }
    private static boolean isBlockedAddress(InetAddress ip) {
        if (ip.isLoopbackAddress() || ip.isAnyLocalAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) {
            return true;
        }
        byte[] b = ip.getAddress();
        if (b.length == 4) {
            return isBlockedV4(b[0] & 0xFF, b[1] & 0xFF);
        }
        if (b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) {
                return true;
            }
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                int o0 = b[12] & 0xFF;
                int o1 = b[13] & 0xFF;
                return o0 == 10 || o0 == 127
                    || (o0 == 172 && (o1 & 0xF0) == 16)
                    || (o0 == 192 && o1 == 168)
                    || isBlockedV4(o0, o1);
            }
        }
        return false;
    }
    /** IPv4 ranges {@link InetAddress} doesn't flag but Python's is_private/is_reserved does. */
    private static boolean isBlockedV4(int o0, int o1) {
        return o0 == 0
            || (o0 == 100 && (o1 & 0xC0) == 64)
            || o0 >= 240;
    }
}
