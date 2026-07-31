package ru.savefood.security;

import org.junit.jupiter.api.Test;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    // Long enough for the negative HS384 fixture too. JwtService itself must
    // still sign every token with HS256 regardless of the secret length.
    private static final String SECRET = "test-secret-key-for-junit-0123456789abcdef"
        + "0123456789abcdef0123456789abcdef";
    private final JwtService service = new JwtService(SECRET);

    @Test
    void tokenRoundtrip() {
        String token = service.create("alice", "volunteer", 7);
        CurrentUser user = service.decode(token);
        assertThat(user).isNotNull();
        assertThat(user.sub()).isEqualTo("alice");
        assertThat(user.role()).isEqualTo("volunteer");
        assertThat(user.relatedId()).isEqualTo(7);
    }

    @Test
    void tokensAreAlwaysHs256EvenWithALongSecret() {
        String token = service.create("alice", "volunteer", 7);
        String header = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]),
            StandardCharsets.UTF_8);
        assertThat(header).contains("\"HS256\"");
    }

    @Test
    void hs384TokenIsRejected() {
        String token = Jwts.builder().subject("alice").claim("role", "volunteer")
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS384)
            .compact();
        assertThat(service.decode(token)).isNull();
    }

    @Test
    void payloadMatchesClaims() {
        String token = service.create("bob", "shop", 42);
        Map<String, Object> payload = service.payload(token);
        assertThat(payload.get("sub")).isEqualTo("bob");
        assertThat(payload.get("role")).isEqualTo("shop");
        assertThat(((Number) payload.get("related_id")).intValue()).isEqualTo(42);
    }

    @Test
    void expiredTokenRejected() {
        // Sign a claims blob with a negative TTL (already expired).
        String token = service.signClaims(Map.of("sub", "carol"), -1);
        assertThat(service.decode(token)).isNull();
        assertThat(service.readClaims(token)).isNull();
    }

    @Test
    void garbageTokenRejected() {
        assertThat(service.decode("not.a.jwt")).isNull();
        assertThat(service.decode("")).isNull();
        assertThat(service.readClaims("garbage")).isNull();
    }

    @Test
    void signClaimsRoundtrip() {
        Map<String, Object> claims = Map.of("action", "oauth-state", "nonce", "xyz");
        String token = service.signClaims(claims, 5);
        Map<String, Object> read = service.readClaims(token);
        assertThat(read).isNotNull();
        assertThat(read.get("action")).isEqualTo("oauth-state");
        assertThat(read.get("nonce")).isEqualTo("xyz");
    }

    @Test
    void shortSecretRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> new JwtService("too-short"));
    }
}
