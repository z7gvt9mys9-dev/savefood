package ru.savefood.util;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-ticket QR secret: payload format + uniqueness (pure helpers, no DB).
 * Ported from tests/test_qr_secret.py.
 */
class QrTest {

    @Test
    void qrCodeIncludesSecret() {
        assertThat(Qr.buildCode(42, "AbC1_2-x")).isEqualTo("SF-42-AbC1_2-x");
    }

    @Test
    void qrCodeFallsBackWithoutSecret() {
        // Legacy pre-migration tickets have no secret — keep the bare form working.
        assertThat(Qr.buildCode(42, null)).isEqualTo("SF-42");
        assertThat(Qr.buildCode(42, "")).isEqualTo("SF-42");
    }

    @Test
    void secretIsUnguessableAndUnique() {
        Set<String> secretsSeen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            secretsSeen.add(Qr.generateSecret());
        }
        assertThat(secretsSeen).hasSize(200);  // no collisions
        assertThat(secretsSeen).allMatch(s -> s.length() >= 8);
        // url-safe alphabet only, so it survives the SF-{id}-{secret} regex
        Pattern urlSafe = Pattern.compile("[A-Za-z0-9_-]+");
        assertThat(secretsSeen).allMatch(s -> urlSafe.matcher(s).matches());
    }
}
