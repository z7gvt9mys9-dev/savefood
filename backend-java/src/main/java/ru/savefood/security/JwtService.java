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
@Service
public class JwtService {
    /** Short-lived bearer credential; durable sessions use an independent refresh token. */
    public static final long ACCESS_TOKEN_EXPIRE_MINUTES = 15;
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
    }
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
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
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
    public String signClaims(Map<String, Object> claims, long ttlMinutes) {
        Instant exp = Instant.now().plusSeconds(ttlMinutes * 60);
        return Jwts.builder()
                .claims(new HashMap<>(claims))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
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
