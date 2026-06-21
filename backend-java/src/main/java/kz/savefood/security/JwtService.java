package kz.savefood.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Decodes the HS256 JWTs minted by backend/auth.py. The secret is used as raw
 * UTF-8 bytes for HMAC on both sides (python-jose ⇄ jjwt), so tokens issued by
 * the Python service validate here unchanged. Signature + expiry are verified;
 * an invalid/expired token yields {@code null} (caller maps that to 401).
 */
@Service
public class JwtService {

    /** 24 hours, matching auth.py {@code ACCESS_TOKEN_EXPIRE_MINUTES}. */
    private static final long ACCESS_TOKEN_EXPIRE_MINUTES = 60 * 24;

    private final SecretKey key;

    public JwtService(@Value("${savefood.jwt-secret:}") String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                "SECRET_KEY env var must be set to a random string of at least 32 characters "
                + "(must match the Python backend).");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @PostConstruct
    void ready() {
        // Fail fast at startup if the key is unusable, mirroring auth.py's guard.
    }

    /**
     * Mints an HS256 access token, the Java analogue of auth.py
     * {@code create_access_token}: the same {@code sub}/{@code role}/{@code related_id}
     * claims, a 24h {@code exp}, and no {@code iat} — so tokens issued here are
     * byte-for-byte interchangeable with the Python backend's.
     */
    public String create(String sub, String role, Integer relatedId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("related_id", relatedId);
        Instant exp = Instant.now().plusSeconds(ACCESS_TOKEN_EXPIRE_MINUTES * 60);
        return Jwts.builder()
                .claims(claims)
                .subject(sub)
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /**
     * The decoded payload as auth.py {@code /me} returns it ({@code sub},
     * {@code role}, {@code related_id}, {@code exp}). The token is assumed already
     * validated by {@link AuthArgumentResolver}; {@code exp} is epoch seconds to
     * match python-jose's numeric claim.
     */
    public Map<String, Object> payload(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sub", claims.getSubject());
        out.put("role", claims.get("role", String.class));
        Object rid = claims.get("related_id");
        out.put("related_id", rid instanceof Number n ? n.intValue() : rid);
        Date exp = claims.getExpiration();
        out.put("exp", exp == null ? null : exp.toInstant().getEpochSecond());
        return out;
    }

    /**
     * Signs an arbitrary short-lived claims blob (HS256, the given TTL), the Java
     * analogue of oauth_routes.py {@code _make_state} — used for the OAuth
     * {@code state} parameter and similar stateless, signed round-trip tokens.
     */
    public String signClaims(Map<String, Object> claims, long ttlMinutes) {
        Instant exp = Instant.now().plusSeconds(ttlMinutes * 60);
        return Jwts.builder()
                .claims(new HashMap<>(claims))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies and decodes a {@link #signClaims} blob, mirroring
     * oauth_routes.py {@code _read_state}: returns the claims map, or {@code null}
     * if the signature is invalid or the token has expired.
     */
    public Map<String, Object> readClaims(String token) {
        try {
            return new LinkedHashMap<>(Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /** @return the decoded principal, or {@code null} if the token is invalid/expired. */
    public CurrentUser decode(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String sub = claims.getSubject();
            String role = claims.get("role", String.class);
            Integer relatedId = null;
            Object rid = claims.get("related_id");
            if (rid instanceof Number n) {
                relatedId = n.intValue();
            }
            return new CurrentUser(sub, role, relatedId);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
