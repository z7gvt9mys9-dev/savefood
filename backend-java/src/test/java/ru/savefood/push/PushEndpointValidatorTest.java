package ru.savefood.push;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class PushEndpointValidatorTest {
    @Test
    void rejectsNonHttpsAndLoopbackEndpointsWithoutNetworkAccess() {
        assertThat(PushEndpointValidator.isAllowed("http://127.0.0.1:8080/admin")).isFalse();
        assertThat(PushEndpointValidator.isAllowed("https://127.0.0.1/internal")).isFalse();
        assertThat(PushEndpointValidator.isAllowed("https://[::1]/internal")).isFalse();
    }
}
