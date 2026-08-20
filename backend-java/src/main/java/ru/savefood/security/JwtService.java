package ru.savefood.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
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
 * Issues and decodes SaveFood HS256 access tokens. Signature, algorithm and
 * expiry are verified; an invalid/expired token yields {@code null} (caller
 * maps that to 401).
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
     * {@code create_access_token}. The JWT subject is the immutable users.id;
     * username remains a separate display/API claim so deleting an account and
     * reusing its username cannot transfer an old token to the new account.
     */
    public String create(int userId, String username, String role, Integer relatedId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        claims.put("role", role);
        claims.put("related_id", relatedId);
        Instant exp = Instant.now().plusSeconds(ACCESS_TOKEN_EXPIRE_MINUTES * 60);
        return Jwts.builder()
                .claims(claims)
                .subject(Integer.toString(userId))
                .expiration(Date.from(exp))
                // JJWT otherwise picks HS384/HS512 from a long enough secret.
                // geows and the legacy services intentionally accept HS256 only.
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * The API-compatible {@code /me} payload ({@code sub}, {@code role},
     * {@code related_id}, {@code exp}). Its public {@code sub} remains the username
     * claim even though the signed JWT subject is the immutable user id.
     */
    public Map<String, Object> payload(String token) {
        Claims claims = parseHs256(token).getPayload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sub", claims.get("username", String.class));
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
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies and decodes a {@link #signClaims} blob, mirroring
     * oauth_routes.py {@code _read_state}: returns the claims map, or {@code null}
     * if the signature is invalid or the token has expired.
     */
    public Map<String, Object> readClaims(String token) {
        try {
            return new LinkedHashMap<>(parseHs256(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /** @return the decoded principal, or {@code null} if the token is invalid/expired. */
    public CurrentUser decode(String token) {
        try {
            Claims claims = parseHs256(token).getPayload();
            int userId = Integer.parseInt(claims.getSubject());
            if (userId <= 0) {
                return null;
            }
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
            Integer relatedId = null;
            Object rid = claims.get("related_id");
            if (rid instanceof Number n) {
                relatedId = n.intValue();
            }
            return new CurrentUser(userId, username, role, relatedId);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Verify both the signature and the explicit algorithm contract.  A long HMAC
     * key can technically verify HS384/HS512 too; accepting those here would make
     * Java-issued tokens incompatible with geows and weaken the single-algorithm
     * policy.
     */
    private Jws<Claims> parseHs256(String token) {
        Jws<Claims> signed = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token);
        if (!"HS256".equals(signed.getHeader().getAlgorithm())) {
            throw new JwtException("Unexpected JWT signing algorithm");
        }
        return signed;
    }
}
