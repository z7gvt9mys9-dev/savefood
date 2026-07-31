package ru.savefood.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeoTest {

    @Test
    void acceptsOnlyFiniteWgs84CoordinatePairs() {
        assertThat(Geo.isValidCoordinates(-90.0, -180.0)).isTrue();
        assertThat(Geo.isValidCoordinates(90.0, 180.0)).isTrue();
        assertThat(Geo.isValidCoordinates(90.00001, 0.0)).isFalse();
        assertThat(Geo.isValidCoordinates(0.0, -180.00001)).isFalse();
        assertThat(Geo.isValidCoordinates(Double.NaN, 0.0)).isFalse();
        assertThat(Geo.isValidCoordinates(0.0, Double.POSITIVE_INFINITY)).isFalse();
        assertThat(Geo.isValidCoordinates(null, 0.0)).isFalse();
    }
}
