package ru.savefood.volunteer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Weight of a lot as the volunteer experiences it — the input to the carrying
 * capacity gate (§14).
 */
class LotWeightTest {

    private static Map<String, Object> lot(Object initial, Object quantity, Object unitWeight) {
        Map<String, Object> m = new HashMap<>();
        m.put("initial_quantity", initial);
        m.put("quantity", quantity);
        m.put("unit_weight_kg", unitWeight);
        return m;
    }

    /**
     * The live `quantity` is already reduced by outstanding reservations, but the
     * volunteer still carries every unit — so capacity must be judged against
     * `initial_quantity`.
     */
    @Test
    void usesInitialQuantityNotTheReservedRemainder() {
        assertThat(VolunteerService.lotWeightKg(lot(20.0, 3.0, 1.0))).isEqualTo(20.0);
    }

    @Test
    void multipliesUnitsByPerUnitWeight() {
        assertThat(VolunteerService.lotWeightKg(lot(12.0, 12.0, 0.5))).isEqualTo(6.0);
        assertThat(VolunteerService.lotWeightKg(lot(4.0, 4.0, 2.5))).isEqualTo(10.0);
    }

    /** Legacy rows predate initial_quantity; fall back to the live quantity. */
    @Test
    void fallsBackToQuantityWhenInitialIsMissing() {
        assertThat(VolunteerService.lotWeightKg(lot(null, 7.0, 1.0))).isEqualTo(7.0);
    }

    /** A missing or nonsensical per-unit weight must not zero the lot out. */
    @Test
    void treatsMissingOrZeroUnitWeightAsOneKg() {
        assertThat(VolunteerService.lotWeightKg(lot(5.0, 5.0, null))).isEqualTo(5.0);
        assertThat(VolunteerService.lotWeightKg(lot(5.0, 5.0, 0.0))).isEqualTo(5.0);
    }
}
