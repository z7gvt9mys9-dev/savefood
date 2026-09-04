package ru.savefood.volunteer;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.savefood.web.ApiException;
class RoutePrerequisiteTest {
    @Test
    void deliveryCannotPrecedeShopPickup() {
        List<Map<String, Object>> points = List.of(
            Map.of("kind", "shop", "done", false),
            Map.of("kind", "ticket", "ticket_id", 7));
        assertThatThrownBy(() -> VolunteerService.ensurePickupCompleted(points))
            .isInstanceOfSatisfying(ApiException.class, e -> org.assertj.core.api.Assertions
                .assertThat(e.getStatus()).isEqualTo(409));
    }
    @Test
    void deliveryMayProceedAfterShopPickup() {
        List<Map<String, Object>> points = List.of(
            Map.of("kind", "shop", "done", true),
            Map.of("kind", "ticket", "ticket_id", 7));
        assertThatCode(() -> VolunteerService.ensurePickupCompleted(points)).doesNotThrowAnyException();
    }
}
