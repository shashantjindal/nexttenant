package com.househunting.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the coordinate-order convention.
 *
 * <p>These assertions look trivial. They are here because getting the axis
 * order wrong produces valid-looking data in the wrong hemisphere rather than
 * an error, and a test is the only thing that notices.
 */
class GeoPointsTest {

    @Test
    @DisplayName("of(latitude, longitude) maps latitude to Y and longitude to X")
    void axisOrderIsLongitudeThenLatitude() {
        // Bandra West: 19.0596 N, 72.8295 E
        Point point = GeoPoints.of(19.0596, 72.8295);

        // JTS stores X = longitude, Y = latitude — the reverse of how the
        // numbers are spoken. This is the assertion that catches a swap.
        assertThat(point.getX()).isEqualTo(72.8295);
        assertThat(point.getY()).isEqualTo(19.0596);

        assertThat(GeoPoints.latitudeOf(point)).isEqualTo(19.0596);
        assertThat(GeoPoints.longitudeOf(point)).isEqualTo(72.8295);
    }

    @Test
    @DisplayName("points carry SRID 4326, which the geography column requires")
    void sridIsSet() {
        assertThat(GeoPoints.of(19.0596, 72.8295).getSRID()).isEqualTo(4326);
    }

    @Test
    @DisplayName("out-of-range coordinates are rejected")
    void rejectsImpossibleCoordinates() {
        assertThatThrownBy(() -> GeoPoints.of(91, 72.8295))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");

        assertThatThrownBy(() -> GeoPoints.of(19.0596, 181))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    @DisplayName("the Mumbai bounds check catches swapped coordinates")
    void mumbaiBoundsCatchSwappedArguments() {
        assertThat(GeoPoints.isWithinMumbai(GeoPoints.of(19.0596, 72.8295))).isTrue();

        // The classic mistake: arguments reversed. Both values are legal
        // coordinates, so nothing throws — the point simply ends up in the
        // Indian Ocean off Somalia, and only a bounds check notices.
        assertThat(GeoPoints.isWithinMumbai(GeoPoints.of(72.8295, 19.0596))).isFalse();

        // Delhi is a valid Indian location but not ours.
        assertThat(GeoPoints.isWithinMumbai(GeoPoints.of(28.6139, 77.2090))).isFalse();
    }
}
