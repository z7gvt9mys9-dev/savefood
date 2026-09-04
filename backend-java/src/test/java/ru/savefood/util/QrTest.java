package ru.savefood.util;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class QrTest {
    @Test
    void qrCodeIncludesSecret() {
        assertThat(Qr.buildCode(42, "AbC1_2-x")).isEqualTo("SF-42-AbC1_2-x");
    }
    @Test
    void qrCodeFallsBackWithoutSecret() {
        assertThat(Qr.buildCode(42, null)).isEqualTo("SF-42");
        assertThat(Qr.buildCode(42, "")).isEqualTo("SF-42");
    }
    @Test
    void secretIsUnguessableAndUnique() {
        Set<String> secretsSeen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            secretsSeen.add(Qr.generateSecret());
        }
        assertThat(secretsSeen).hasSize(200);
        assertThat(secretsSeen).allMatch(s -> s.length() >= 8);
        Pattern urlSafe = Pattern.compile("[A-Za-z0-9_-]+");
        assertThat(secretsSeen).allMatch(s -> urlSafe.matcher(s).matches());
    }
}
