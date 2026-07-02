package kz.savefood.volunteer;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import kz.savefood.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Port of tests/test_geo_verification.py — GPS anti-fraud hardening + recipient
 * address privacy:
 * <ul>
 *   <li>coarsenCoord: open requests on the volunteer map must not reveal the house;</li>
 *   <li>verifyPositionAgainstPings: client-supplied confirmation coordinates are
 *       cross-checked against the latest geolocation ping (§13/§27).</li>
 * </ul>
 */
class GeoVerificationTest {

    private Map<String, Object> stubbedLocation;

    /** Only getVolunteerLocation is exercised — a hand stub avoids mocking a concrete class. */
    private final VolunteerRepository repo = new VolunteerRepository(null) {
        @Override
        public Map<String, Object> getVolunteerLocation(int volId) {
            return stubbedLocation;
        }
    };
    private final VolunteerService svc = new VolunteerService(null, repo, null, null, "Asia/Almaty");

    // ── coarsenCoord ─────────────────────────────────────────────────────────────

    @Test
    void coarsenSnapsToGrid() {
        double v = VolunteerService.coarsenCoord(43.238949);
        double grid = VolunteerService.COARSE_GRID_DEG;
        assertThat(Math.abs(v / grid - Math.round(v / grid))).isLessThan(1e-6);
    }

    @Test
    void coarsenMovesPointLessThanGrid() {
        double lat = 43.238949;
        assertThat(Math.abs(VolunteerService.coarsenCoord(lat) - lat))
            .isLessThanOrEqualTo(VolunteerService.COARSE_GRID_DEG / 2 + 1e-9);
    }

    @Test
    void coarsenHidesBuildingScaleDifferences() {
        // Two addresses ~100 m apart must collapse into the same grid cell more
        // often than not; exact equality for these two known points.
        assertThat(VolunteerService.coarsenCoord(43.2381)).isEqualTo(VolunteerService.coarsenCoord(43.2389));
    }

    @Test
    void coarsenIsDeterministic() {
        // No jitter: repeated map loads must not let an observer average out noise.
        assertThat(VolunteerService.coarsenCoord(43.238949)).isEqualTo(VolunteerService.coarsenCoord(43.238949));
    }

    // ── verifyPositionAgainstPings ───────────────────────────────────────────────

    private void stubLocation(Map<String, Object> loc) {
        stubbedLocation = loc;
    }

    private static Map<String, Object> location(Double lat, Double lon, OffsetDateTime updatedAt) {
        Map<String, Object> loc = new HashMap<>();
        loc.put("lat", lat);
        loc.put("lon", lon);
        loc.put("updated_at", updatedAt);
        return loc;
    }

    @Test
    void freshPingFarAwayRejected() {
        stubLocation(location(43.25, 76.95, OffsetDateTime.now().minusMinutes(1)));
        // ~0.1° of latitude ≈ 11 km away from the claimed position
        assertThatThrownBy(() -> svc.verifyPositionAgainstPings(1, 43.35, 76.95))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void freshPingNearbyPasses() {
        stubLocation(location(43.25, 76.95, OffsetDateTime.now().minusMinutes(1)));
        assertThatCode(() -> svc.verifyPositionAgainstPings(1, 43.251, 76.951)) // ~140 m
            .doesNotThrowAnyException();
    }

    @Test
    void stalePingNotCompared() {
        // A tracker that went quiet an hour ago must not block an honest delivery.
        stubLocation(location(43.25, 76.95, OffsetDateTime.now().minusHours(1)));
        assertThatCode(() -> svc.verifyPositionAgainstPings(1, 44.0, 77.0)).doesNotThrowAnyException();
    }

    @Test
    void noPingHistoryPasses() {
        stubLocation(null);
        assertThatCode(() -> svc.verifyPositionAgainstPings(1, 43.25, 76.95)).doesNotThrowAnyException();
        stubLocation(location(null, null, null));
        assertThatCode(() -> svc.verifyPositionAgainstPings(1, 43.25, 76.95)).doesNotThrowAnyException();
    }
}
