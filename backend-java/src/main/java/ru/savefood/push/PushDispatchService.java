package ru.savefood.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Port of the dispatch half of backend/push_service.py — the Web Push (VAPID,
 * RFC 8291 {@code aes128gcm}) and Firebase Cloud Messaging (HTTP v1) sends that
 * the storage-only {@link PushService} deliberately left on the Python notifier.
 *
 * <p>{@link #notifyRole} mirrors {@code notify_role}: it fans the message out to
 * every configured channel (Web Push and/or FCM), each independently a no-op
 * without its own config or recipients, and runs off the request thread. Dead
 * Web Push endpoints (404/410) and stale FCM tokens (404/UNREGISTERED) are pruned.
 *
 * <p>The Web Push crypto is implemented with JDK primitives (ECDH P-256, HKDF via
 * HmacSHA256, AES-128-GCM) and the VAPID JWT (ES256) via jjwt; validate against a
 * live push endpoint before flipping the {@code /push} module off Python.
 */
@Service
public class PushDispatchService {

    private static final Logger log = Logger.getLogger(PushDispatchService.class.getName());
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "push-dispatch");
        t.setDaemon(true);
        return t;
    });
    private final SecureRandom random = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final String vapidPublicKey;
    private final String vapidPrivateKey;
    private final String vapidSubject;

    private final boolean fcmEnabled;
    private final String fcmProjectId;
    private final String fcmCredentialsJson;
    private final String fcmCredentialsFile;

    // Cached FCM OAuth2 access token (refreshed a minute before expiry).
    private volatile String fcmToken;
    private volatile Instant fcmTokenExp = Instant.EPOCH;

    public PushDispatchService(
            JdbcTemplate jdbc,
            @Value("${savefood.push.vapid-public-key:}") String vapidPublicKey,
            @Value("${savefood.push.vapid-private-key:}") String vapidPrivateKey,
            @Value("${savefood.push.vapid-subject:mailto:support@savefood.local}") String vapidSubject,
            @Value("${savefood.push.fcm-enabled:false}") boolean fcmEnabled,
            @Value("${savefood.push.fcm-project-id:}") String fcmProjectId,
            @Value("${savefood.push.fcm-credentials-json:}") String fcmCredentialsJson,
            @Value("${savefood.push.fcm-credentials-file:}") String fcmCredentialsFile) {
        this.jdbc = jdbc;
        this.vapidPublicKey = vapidPublicKey;
        this.vapidPrivateKey = vapidPrivateKey;
        this.vapidSubject = vapidSubject;
        this.fcmEnabled = fcmEnabled;
        this.fcmProjectId = fcmProjectId;
        this.fcmCredentialsJson = fcmCredentialsJson;
        this.fcmCredentialsFile = fcmCredentialsFile;
    }

    public boolean isConfigured() {
        return !vapidPublicKey.isEmpty() && !vapidPrivateKey.isEmpty();
    }

    public boolean fcmIsConfigured() {
        return fcmEnabled && !fcmProjectId.isEmpty()
            && (!fcmCredentialsJson.isEmpty() || !fcmCredentialsFile.isEmpty());
    }

    /** Telegram messages carry HTML (&lt;b&gt;…&lt;/b&gt;); browser pushes are plain. */
    static String stripHtml(String text) {
        return text == null ? "" : text.replaceAll("<[^>]+>", "");
    }

    /**
     * Push to the account bound to (role, related_id). Dispatches to every
     * configured channel off the calling thread; each is a no-op without config.
     */
    public void notifyRole(String role, Integer relatedId, String text, String url) {
        if (relatedId == null || relatedId == 0) {
            return;
        }
        String body = stripHtml(text);
        String link = (url == null || url.isEmpty()) ? "/" : url;

        if (isConfigured()) {
            try {
                Integer userId = jdbc.query(
                    "SELECT id FROM users WHERE role = ? AND related_id = ?",
                    rs -> rs.next() ? rs.getInt("id") : null, role, relatedId);
                if (userId != null) {
                    pool.submit(() -> sendToUser(userId, "SaveFood", body, link));
                }
            } catch (Exception e) {
                log.warning("[push] notify_role failed: " + e.getMessage());
            }
        }
        if (fcmIsConfigured()) {
            pool.submit(() -> sendFcmToRole(role, relatedId, "SaveFood", body, link));
        }
    }

    // ── Web Push (RFC 8291 aes128gcm + RFC 8292 VAPID) ───────────────────────

    private void sendToUser(int userId, String title, String body, String url) {
        List<Map<String, Object>> subs = jdbc.queryForList(
            "SELECT endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?", userId);
        if (subs.isEmpty()) {
            return;
        }
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(Map.of("title", title, "body", body, "url", url));
        } catch (Exception e) {
            return;
        }
        for (Map<String, Object> sub : subs) {
            String endpoint = (String) sub.get("endpoint");
            try {
                byte[] ciphertext = encrypt(payload,
                    b64urlDecode((String) sub.get("p256dh")),
                    b64urlDecode((String) sub.get("auth")));
                String jwt = vapidJwt(endpoint);
                HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", "86400")
                    .header("Authorization", "vapid t=" + jwt + ", k=" + stripPadding(vapidPublicKey))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(ciphertext))
                    .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status == 404 || status == 410) {
                    jdbc.update("DELETE FROM push_subscriptions WHERE endpoint = ?", endpoint);
                } else if (status >= 300) {
                    log.warning("[push] send failed (" + status + ")");
                }
            } catch (Exception e) {
                log.warning("[push] send failed: " + e.getMessage());
            }
        }
    }

    /** RFC 8291 aes128gcm body: header(salt|rs|idlen|key) || AES-128-GCM(record). */
    private byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) throws Exception {
        ECParameterSpec p256 = p256Params();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair as = kpg.generateKeyPair();
        byte[] asPublic = encodeUncompressed((ECPublicKey) as.getPublic());

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(as.getPrivate());
        ka.doPhase(decodePublic(uaPublic, p256), true);
        byte[] ecdh = ka.generateSecret();

        // ikm = HKDF(salt=auth, ikm=ecdh, info="WebPush: info\0"||ua||as, 32)
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hkdf(authSecret, ecdh, keyInfo, 32);

        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] prk = hmac(salt, ikm);  // HKDF-Extract
        byte[] cek = hkdfExpand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdfExpand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        // Single record: plaintext || 0x02 delimiter.
        byte[] record = concat(plaintext, new byte[]{0x02});
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(record);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(salt);
        out.write(new byte[]{0x00, 0x00, 0x10, 0x00});  // rs = 4096, uint32 BE
        out.write(asPublic.length);                       // idlen
        out.write(asPublic);
        out.write(ciphertext);
        return out.toByteArray();
    }

    private String vapidJwt(String endpoint) throws Exception {
        URI u = URI.create(endpoint);
        String aud = u.getScheme() + "://" + u.getHost();
        return Jwts.builder()
            .audience().add(aud).and()
            .subject(vapidSubject)
            .expiration(Date.from(Instant.now().plus(Duration.ofHours(12))))
            .signWith(vapidSigningKey(), Jwts.SIG.ES256)
            .compact();
    }

    private ECPrivateKey vapidSigningKey() throws Exception {
        byte[] s = b64urlDecode(vapidPrivateKey);
        ECParameterSpec p256 = p256Params();
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPrivateKey) kf.generatePrivate(
            new ECPrivateKeySpec(new java.math.BigInteger(1, s), p256));
    }

    // ── FCM (Firebase Cloud Messaging, HTTP v1) ──────────────────────────────

    private void sendFcmToRole(String role, int relatedId, String title, String body, String url) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT token FROM fcm_tokens WHERE role = ? AND related_id = ?", role, relatedId);
        if (rows.isEmpty()) {
            return;
        }
        String accessToken;
        try {
            accessToken = fcmAccessToken();
        } catch (Exception e) {
            log.warning("[fcm] could not obtain access token: " + e.getMessage());
            return;
        }
        String endpoint = "https://fcm.googleapis.com/v1/projects/" + fcmProjectId + "/messages:send";
        for (Map<String, Object> r : rows) {
            String token = (String) r.get("token");
            try {
                Map<String, Object> message = Map.of("message", Map.of(
                    "token", token,
                    "notification", Map.of("title", title, "body", body),
                    "data", Map.of("url", url)));  // FCM data is string-only
                HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(message)))
                    .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    continue;
                }
                if (resp.statusCode() == 404 || resp.body().contains("UNREGISTERED")) {
                    jdbc.update("DELETE FROM fcm_tokens WHERE token = ?", token);
                } else {
                    log.warning("[fcm] send failed (" + resp.statusCode() + "): "
                        + resp.body().substring(0, Math.min(200, resp.body().length())));
                }
            } catch (Exception e) {
                log.warning("[fcm] send failed: " + e.getMessage());
            }
        }
    }

    private synchronized String fcmAccessToken() throws Exception {
        if (fcmToken != null && Instant.now().isBefore(fcmTokenExp)) {
            return fcmToken;
        }
        JsonNode sa = mapper.readTree(fcmCredentialsJson.isEmpty()
            ? java.nio.file.Files.readAllBytes(java.nio.file.Path.of(fcmCredentialsFile))
            : fcmCredentialsJson.getBytes(StandardCharsets.UTF_8));
        String clientEmail = sa.path("client_email").asText();
        String tokenUri = sa.path("token_uri").asText("https://oauth2.googleapis.com/token");
        var privateKey = parsePkcs8Rsa(sa.path("private_key").asText());

        Instant now = Instant.now();
        String assertion = Jwts.builder()
            .issuer(clientEmail)
            .audience().add(tokenUri).and()
            .claim("scope", FCM_SCOPE)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(Duration.ofHours(1))))
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();

        String form = "grant_type="
            + java.net.URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
            + "&assertion=" + assertion;
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUri))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        fcmToken = json.path("access_token").asText();
        fcmTokenExp = now.plusSeconds(Math.max(60, json.path("expires_in").asLong(3600) - 60));
        return fcmToken;
    }

    private static java.security.interfaces.RSAPrivateKey parsePkcs8Rsa(String pem) throws Exception {
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return (java.security.interfaces.RSAPrivateKey) KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    // ── crypto helpers ───────────────────────────────────────────────────────

    /** HKDF (extract + expand) per RFC 5869, output length L (<=32). */
    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws Exception {
        return hkdfExpand(hmac(salt, ikm), info, length);
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        byte[] t = hmac(prk, concat(info, new byte[]{0x01}));
        byte[] out = new byte[length];
        System.arraycopy(t, 0, out, 0, length);
        return out;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static ECParameterSpec p256Params() throws Exception {
        AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        return ap.getParameterSpec(ECParameterSpec.class);
    }

    private static ECPublicKey decodePublic(byte[] uncompressed, ECParameterSpec params) throws Exception {
        // 0x04 || X(32) || Y(32)
        byte[] x = new byte[32];
        byte[] y = new byte[32];
        System.arraycopy(uncompressed, 1, x, 0, 32);
        System.arraycopy(uncompressed, 33, y, 0, 32);
        ECPoint point = new ECPoint(new java.math.BigInteger(1, x), new java.math.BigInteger(1, y));
        return (ECPublicKey) KeyFactory.getInstance("EC")
            .generatePublic(new ECPublicKeySpec(point, params));
    }

    private static byte[] encodeUncompressed(ECPublicKey key) {
        ECPoint w = key.getW();
        byte[] x = toFixed(w.getAffineX(), 32);
        byte[] y = toFixed(w.getAffineY(), 32);
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    private static byte[] toFixed(java.math.BigInteger v, int len) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[len];
        if (b.length > len) {
            System.arraycopy(b, b.length - len, out, 0, len);
        } else {
            System.arraycopy(b, 0, out, len - b.length, b.length);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    /** Tolerant base64url decode (browsers send unpadded url-safe keys). */
    private static byte[] b64urlDecode(String s) {
        String t = s.replace('+', '-').replace('/', '_');
        int pad = (4 - t.length() % 4) % 4;
        return Base64.getUrlDecoder().decode(t + "=".repeat(pad));
    }

    private static String stripPadding(String s) {
        int i = s.indexOf('=');
        return i < 0 ? s : s.substring(0, i);
    }
}
