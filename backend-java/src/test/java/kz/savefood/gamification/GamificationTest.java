package kz.savefood.gamification;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;

class GamificationTest {

    @Test
    void zeroPointsIsNovice() {
        Map<String, Object> lvl = Gamification.computeLevel(0, 0);
        assertThat(lvl.get("code")).isEqualTo("novice");
        assertThat(lvl.get("points")).isEqualTo(0.0);
        assertThat(lvl.get("next_code")).isEqualTo("helper");
        assertThat(lvl.get("progress")).isEqualTo(0.0);
    }

    @Test
    void pointsBlendDeliveriesAndKg() {
        // 3 deliveries × 10 + 25 kg × 1 = 55
        assertThat(Gamification.computePoints(3, 25)).isEqualTo(55.0);
    }

    @Test
    void thresholdBoundaryPromotes() {
        // exactly 50 points (5 deliveries) → helper
        Map<String, Object> lvl = Gamification.computeLevel(5, 0);
        assertThat(lvl.get("code")).isEqualTo("helper");
    }

    @Test
    void topLevelHasNoNext() {
        Map<String, Object> lvl = Gamification.computeLevel(1000, 1000);
        assertThat(lvl.get("code")).isEqualTo("city_hero");
        assertThat(lvl.get("next_code")).isNull();
        assertThat(lvl.get("progress")).isEqualTo(1.0);
        assertThat(lvl.get("points_to_next")).isEqualTo(0.0);
    }

    @Test
    void progressIsWithinUnitInterval() {
        for (int d : new int[]{0, 1, 7, 19, 60, 149, 500}) {
            Map<String, Object> lvl = Gamification.computeLevel(d, d * 2.5);
            double progress = (double) lvl.get("progress");
            assertThat(progress).isBetween(0.0, 1.0);
        }
    }
}
