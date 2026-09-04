package ru.savefood.util;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ClampTest {
    @Test
    void withinRangeUnchanged() {
        assertThat(Clamp.clamp(5, 1, 10)).isEqualTo(5);
    }
    @Test
    void belowLoSnapsToLo() {
        assertThat(Clamp.clamp(0, 1, 10)).isEqualTo(1);
    }
    @Test
    void aboveHiSnapsToHi() {
        assertThat(Clamp.clamp(20, 1, 10)).isEqualTo(10);
    }
    @Test
    void atBoundariesIsInclusive() {
        assertThat(Clamp.clamp(1, 1, 10)).isEqualTo(1);
        assertThat(Clamp.clamp(10, 1, 10)).isEqualTo(10);
    }
}
