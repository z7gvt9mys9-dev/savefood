package ru.savefood.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Outgoing webhooks for Enterprise shops, ported from webhook_service.py.
 * Delivery runs off the request thread (a daemon executor here, daemon threads
 * in Python); the body is HMAC-SHA256-signed with the webhook secret and sent
 * with {@code X-SaveFood-Event} / {@code X-SaveFood-Signature} headers. No
 * retries — {@code last_status}/{@code last_delivery_at} surface failures in the
 * dashboard. A best-effort SSRF guard blocks delivery to internal addresses.
 */
@Service
public class WebhookService {

    private static final Logger log = Logger.getLogger(WebhookService.class.getName());
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);

    /** The events a partner webhook may subscribe to (webhook_service.py {@code EVENTS}). */
    public static final java.util.List<String> EVENTS =
        java.util.List.of("lot.taken", "lot.confirmed", "receipt.parsed");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService delivery = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "webhook-delivery");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(DELIVERY_TIMEOUT).build();

    public WebhookService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
            delivery.submit(() -> deliver(id, url, secret, event, body));
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

    private void deliver(int webhookId, String url, String secret, String event, byte[] body) {
        Integer status = null;
        if (!isSafeWebhookUrl(url)) {
            log.warning("[webhook] delivery to " + url
                + " blocked: resolves to an internal/unsafe address");
        } else {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(DELIVERY_TIMEOUT)
                    .header("content-type", "application/json")
                    .header("x-savefood-event", event)
                    .header("x-savefood-signature", sign(secret, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
                HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                status = resp.statusCode();
            } catch (Exception e) {
                log.warning("[webhook] delivery to " + url + " failed: " + e);
            }
        }
        try {
            jdbc.update("UPDATE webhooks SET last_status = ?, last_delivery_at = ? WHERE id = ?",
                status, OffsetDateTime.now(), webhookId);
        } catch (RuntimeException ignored) {
            // best-effort, like the Python except: pass
        }
    }

    /**
     * SSRF guard: partners control the URL, so block anything resolving to
     * internal infrastructure. Mirrors the Python {@code ip.is_private} /
     * loopback / link-local / reserved / multicast / unspecified checks.
     *
     * <p>{@link InetAddress}'s built-ins miss two ranges that Python's
     * {@code is_private} catches, so we add them explicitly: IPv6 ULA
     * ({@code fc00::/7}) and IPv4 CGNAT ({@code 100.64.0.0/10}) — plus the
     * reserved {@code 0.0.0.0/8} and {@code 240.0.0.0/4}, and the IPv4-mapped
     * IPv6 form of all of the above.
     *
     * <p>Known limitation (shared with the authoritative Python service): this is
     * a resolve-then-send check, so a DNS-rebinding attacker could in principle
     * flip the record between validation and the HTTP client's own resolution.
     * Closing that fully needs IP-pinning via a custom socket factory; it is left
     * matching the Python source's posture rather than diverging in the port.
     * Redirects are not a vector — the client uses the default
     * {@code Redirect.NEVER} (as does Python's httpx).
     */
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
                return true; // fc00::/7 unique local address
            }
            // IPv4-mapped IPv6 (::ffff:a.b.c.d) — re-check the embedded v4.
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
        return o0 == 0                               // 0.0.0.0/8 (this network)
            || (o0 == 100 && (o1 & 0xC0) == 64)      // 100.64.0.0/10 CGNAT
            || o0 >= 240;                            // 240.0.0.0/4 reserved
    }
}
