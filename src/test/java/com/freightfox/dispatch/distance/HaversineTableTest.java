package com.freightfox.dispatch.distance;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class HaversineTableTest {

    private final DistanceCalculator calculator = new HaversineDistanceCalculator();

    @Test
    void agreesWithCalculatorAroundIndia() {
        assertAgreement(new Random(1), 400, 8.0, 37.0, 68.0, 97.0, 1e-9);
    }

    @Test
    void agreesWithCalculatorAcrossTheGlobe() {
        // Near-antipodal pairs are ill conditioned for any Haversine evaluation; a micrometre is
        // still far below anything that matters for routing.
        assertAgreement(new Random(2), 400, -90.0, 90.0, -180.0, 180.0, 1e-6);
    }

    @Test
    void agreesForNearbyPoints() {
        double[] lats = {28.6139, 28.6139000001, 28.6139, 28.7041};
        double[] lons = {77.2090, 77.2090, 77.2090000001, 77.1025};
        HaversineTable table = new HaversineTable(lats, lons);
        for (int i = 0; i < lats.length; i++) {
            for (int j = 0; j < lats.length; j++) {
                assertThat(table.distanceKm(i, j))
                        .isCloseTo(calculator.distanceKm(lats[i], lons[i], lats[j], lons[j]), within(1e-9));
            }
        }
        assertThat(table.distanceKm(0, 0)).isZero();
    }

    @Test
    void haversineTermIsMonotoneInDistance() {
        double[] lats = {0, 0, 0, 0};
        double[] lons = {0, 1, 5, 120};
        HaversineTable table = new HaversineTable(lats, lons);

        assertThat(table.haversineTerm(0, 1)).isLessThan(table.haversineTerm(0, 2));
        assertThat(table.haversineTerm(0, 2)).isLessThan(table.haversineTerm(0, 3));
        assertThat(table.size()).isEqualTo(4);
    }

    @Test
    void rejectsMismatchedArrays() {
        assertThatThrownBy(() -> new HaversineTable(new double[2], new double[3]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertAgreement(Random random, int count, double minLat, double maxLat, double minLon, double maxLon,
                                 double toleranceKm) {
        double[] lats = new double[count];
        double[] lons = new double[count];
        for (int i = 0; i < count; i++) {
            lats[i] = minLat + random.nextDouble() * (maxLat - minLat);
            lons[i] = minLon + random.nextDouble() * (maxLon - minLon);
        }
        HaversineTable table = new HaversineTable(lats, lons);
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                double expected = calculator.distanceKm(lats[i], lons[i], lats[j], lons[j]);
                assertThat(table.distanceKm(i, j)).as("pair %d,%d", i, j).isCloseTo(expected, within(toleranceKm));
            }
        }
    }
}
