package com.freightfox.dispatch.distance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HaversineDistanceCalculatorTest {

    private static final double CP_LAT = 28.6139;
    private static final double CP_LON = 77.2090;
    private static final double KB_LAT = 28.7041;
    private static final double KB_LON = 77.1025;
    private static final double MUMBAI_LAT = 19.0760;
    private static final double MUMBAI_LON = 72.8777;
    private static final double HALF_CIRCUMFERENCE_KM = Math.PI * HaversineDistanceCalculator.EARTH_RADIUS_KM;

    private final DistanceCalculator calculator = new HaversineDistanceCalculator();

    @Test
    void samePointIsZero() {
        assertThat(calculator.distanceKm(CP_LAT, CP_LON, CP_LAT, CP_LON)).isZero();
        assertThat(calculator.distanceKm(-33.8688, 151.2093, -33.8688, 151.2093)).isZero();
    }

    @Test
    void connaughtPlaceToKarolBaghIsAbout14Point4Km() {
        assertThat(calculator.distanceKm(CP_LAT, CP_LON, KB_LAT, KB_LON)).isCloseTo(14.4, within(0.2));
    }

    @Test
    void delhiToMumbaiIsAbout1150Km() {
        assertThat(calculator.distanceKm(CP_LAT, CP_LON, MUMBAI_LAT, MUMBAI_LON)).isCloseTo(1150.0, within(10.0));
    }

    @Test
    void isSymmetric() {
        assertThat(calculator.distanceKm(CP_LAT, CP_LON, KB_LAT, KB_LON))
                .isEqualTo(calculator.distanceKm(KB_LAT, KB_LON, CP_LAT, CP_LON));
        Random random = new Random(7);
        for (int i = 0; i < 1_000; i++) {
            double lat1 = random.nextDouble() * 180 - 90;
            double lon1 = random.nextDouble() * 360 - 180;
            double lat2 = random.nextDouble() * 180 - 90;
            double lon2 = random.nextDouble() * 360 - 180;
            assertThat(calculator.distanceKm(lat1, lon1, lat2, lon2))
                    .isCloseTo(calculator.distanceKm(lat2, lon2, lat1, lon1), within(1e-9));
        }
    }

    @Test
    void usesEarthRadius6371() {
        assertThat(HaversineDistanceCalculator.EARTH_RADIUS_KM).isEqualTo(6371.0);
        // One degree of latitude along a meridian = R * pi / 180.
        assertThat(calculator.distanceKm(0, 0, 1, 0)).isCloseTo(6371.0 * Math.PI / 180.0, within(1e-9));
    }

    @ParameterizedTest(name = "({0}, {1}) -> ({2}, {3}) = {4} km")
    @CsvSource({
            "0, 0, 0, 180, " + Math.PI * 6371.0,
            "0, 0, 0, -180, " + Math.PI * 6371.0,
            "90, 0, -90, 0, " + Math.PI * 6371.0,
            "45, 30, -45, -150, " + Math.PI * 6371.0,
            "0, -180, 0, 180, 0",
            "90, 0, 90, 135, 0",
            "-90, 10, -90, -170, 0"
    })
    void antipodalAndEdgeCoordinatesAreFinite(double lat1, double lon1, double lat2, double lon2, double expected) {
        double distance = calculator.distanceKm(lat1, lon1, lat2, lon2);

        assertThat(distance).isNotNaN().isFinite().isCloseTo(expected, within(1e-3));
        assertThat(distance).isBetween(0.0, HALF_CIRCUMFERENCE_KM + 1e-9);
    }

    @Test
    void neverNegativeAndNeverAboveHalfCircumference() {
        Random random = new Random(11);
        for (int i = 0; i < 10_000; i++) {
            double distance = calculator.distanceKm(random.nextDouble() * 180 - 90, random.nextDouble() * 360 - 180,
                    random.nextDouble() * 180 - 90, random.nextDouble() * 360 - 180);
            assertThat(distance).isNotNaN().isBetween(0.0, HALF_CIRCUMFERENCE_KM + 1e-9);
        }
    }

    @Test
    void haversineTermConversionClampsOutOfRangeValues() {
        assertThat(HaversineDistanceCalculator.kmFromHaversineTerm(-1e-17)).isZero();
        assertThat(HaversineDistanceCalculator.kmFromHaversineTerm(1.0 + 1e-15)).isEqualTo(HALF_CIRCUMFERENCE_KM);
        assertThat(HaversineDistanceCalculator.kmFromHaversineTerm(0.5)).isCloseTo(HALF_CIRCUMFERENCE_KM / 2, within(1e-9));
    }
}
