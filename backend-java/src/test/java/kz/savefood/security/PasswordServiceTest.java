package kz.savefood.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordServiceTest {

    private final PasswordService service = new PasswordService();

    @Test
    void passwordRoundtrip() {
        String hash = service.hash("s3cret-password");
        assertThat(service.verify("s3cret-password", hash)).isTrue();
        assertThat(service.verify("wrong", hash)).isFalse();
    }

    @Test
    void malformedHashReadsAsWrongPassword() {
        assertThat(service.verify("anything", "not-a-bcrypt-hash")).isFalse();
        assertThat(service.verify("anything", "")).isFalse();
        assertThat(service.verify("anything", null)).isFalse();
    }
}
