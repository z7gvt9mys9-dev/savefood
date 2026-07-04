package ru.savefood.volunteer;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityServiceTest {

    private final AvailabilityService service = new AvailabilityService("Europe/Moscow");

    // Wednesday 19:30 (weekday() == 2 in Mon=0 convention)
    private static final LocalDateTime WED_1930 = LocalDateTime.of(2026, 6, 10, 19, 30);

    @Test
    void emptyCalendarMeansAlwaysAvailable() {
        assertThat(service.isAvailableNow(null, WED_1930)).isTrue();
        assertThat(service.isAvailableNow("", WED_1930)).isTrue();
        assertThat(service.isAvailableNow("[]", WED_1930)).isTrue();
    }

    @Test
    void windowMatchSameDay() {
        String avail = "[{\"day\":2,\"start\":\"18:00\",\"end\":\"21:00\"}]";
        assertThat(service.isAvailableNow(avail, WED_1930)).isTrue();
    }

    @Test
    void outsideWindowNotAvailable() {
        String avail = "[{\"day\":2,\"start\":\"08:00\",\"end\":\"12:00\"}]";
        assertThat(service.isAvailableNow(avail, WED_1930)).isFalse();
    }

    @Test
    void wrongWeekdayNotAvailable() {
        String avail = "[{\"day\":5,\"start\":\"18:00\",\"end\":\"21:00\"}]";
        assertThat(service.isAvailableNow(avail, WED_1930)).isFalse();
    }

    @Test
    void overnightWindow() {
        String avail = "[{\"day\":2,\"start\":\"22:00\",\"end\":\"02:00\"}]";
        assertThat(service.isAvailableNow(avail, LocalDateTime.of(2026, 6, 10, 23, 30))).isTrue();
        assertThat(service.isAvailableNow(avail, LocalDateTime.of(2026, 6, 10, 1, 0))).isTrue();
        assertThat(service.isAvailableNow(avail, LocalDateTime.of(2026, 6, 10, 12, 0))).isFalse();
    }

    @Test
    void malformedWindowIsSkippedNotCrash() {
        // The bad entry must be skipped; the valid entry still matches.
        String avail = "[{\"day\":2,\"start\":\"bad\"},{\"day\":2,\"start\":\"18:00\",\"end\":\"21:00\"}]";
        assertThat(service.isAvailableNow(avail, WED_1930)).isTrue();
    }

    @Test
    void unparsableJsonMeansAlwaysAvailable() {
        assertThat(service.isAvailableNow("{not json}", WED_1930)).isTrue();
    }
}
