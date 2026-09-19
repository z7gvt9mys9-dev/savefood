package ru.savefood.needy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.savefood.volunteer.AvailabilityService;

class DeliveryAvailabilityServiceTest {
    private final DeliveryAvailabilityService service = new DeliveryAvailabilityService(
        mock(JdbcTemplate.class), new AvailabilityService("Europe/Moscow"), true);

    @Test
    void noOnlineVolunteersUsesFallbackEstimate() {
        assertThat(service.summarize(List.of(), 45))
            .containsEntry("status", "no_online")
            .containsEntry("online_volunteers", 0)
            .containsEntry("free_volunteers", 0)
            .containsEntry("estimated_wait_minutes", 60);
    }

    @Test
    void allOnlineVolunteersBusyUsesAverageRouteEstimate() {
        assertThat(service.summarize(List.of(
            Map.of("availability", "[]", "busy", true, "active_minutes", 12)), 37))
            .containsEntry("status", "busy")
            .containsEntry("online_volunteers", 1)
            .containsEntry("free_volunteers", 0)
            .containsEntry("estimated_wait_minutes", 25);
    }

    @Test
    void freeVolunteerNeedsNoWarningOrWait() {
        assertThat(service.summarize(List.of(
            Map.of("availability", "[]", "busy", false)), 45))
            .containsEntry("status", "available")
            .containsEntry("free_volunteers", 1)
            .containsEntry("estimated_wait_minutes", 0);
    }
}
