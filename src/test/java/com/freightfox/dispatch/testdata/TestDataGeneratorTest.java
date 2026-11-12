package com.freightfox.dispatch.testdata;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TestDataGeneratorTest {

    private static final double EARTH_RADIUS_KM = 6371.0;
    /** 15 km offset plus a little slack for the flat-earth degree conversion and 6-decimal rounding. */
    private static final double MAX_DISTANCE_FROM_CITY_KM = TestDataGenerator.MAX_OFFSET_KM + 0.2;
    private static final Map<String, IndianCity> CITY_BY_ADDRESS = Arrays.stream(IndianCity.values())
            .collect(Collectors.toMap(TestDataGenerator::addressNear, Function.identity()));

    private final TestDataGenerator generator = new TestDataGenerator();

    @Test
    void sameSeedProducesIdenticalData() {
        GeneratedDataset first = generator.generate(500, 50, 42L);
        GeneratedDataset second = generator.generate(500, 50, 42L);

        assertThat(fingerprintOrders(first.orders())).isEqualTo(fingerprintOrders(second.orders()));
        assertThat(fingerprintVehicles(first.vehicles())).isEqualTo(fingerprintVehicles(second.vehicles()));
    }

    @Test
    void differentSeedProducesDifferentData() {
        GeneratedDataset first = generator.generate(500, 50, 42L);
        GeneratedDataset second = generator.generate(500, 50, 43L);

        assertThat(fingerprintOrders(first.orders())).isNotEqualTo(fingerprintOrders(second.orders()));
        assertThat(fingerprintVehicles(first.vehicles())).isNotEqualTo(fingerprintVehicles(second.vehicles()));
    }

    @Test
    void vehiclesForASeedDoNotDependOnTheOrderCount() {
        GeneratedDataset small = generator.generate(10, 20, 7L);
        GeneratedDataset large = generator.generate(1_000, 20, 7L);

        assertThat(fingerprintVehicles(small.vehicles())).isEqualTo(fingerprintVehicles(large.vehicles()));
        assertThat(fingerprintOrders(large.orders().subList(0, 10))).isEqualTo(fingerprintOrders(small.orders()));
    }

    @Test
    void generatesRequestedCounts() {
        GeneratedDataset dataset = generator.generate(1234, 56, 1L);

        assertThat(dataset.orders()).hasSize(1234);
        assertThat(dataset.vehicles()).hasSize(56);
    }

    @Test
    void zeroCountsGiveEmptyLists() {
        GeneratedDataset dataset = generator.generate(0, 0, 1L);

        assertThat(dataset.orders()).isEmpty();
        assertThat(dataset.vehicles()).isEmpty();
    }

    @Test
    void rejectsNegativeOrTooLargeCounts() {
        assertThatThrownBy(() -> generator.generate(-1, 1, 1L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderCount");
        assertThatThrownBy(() -> generator.generate(1, -1, 1L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vehicleCount");
        assertThatThrownBy(() -> generator.generate(TestDataGenerator.MAX_ORDER_COUNT + 1, 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(1, TestDataGenerator.MAX_VEHICLE_COUNT + 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsAreZeroPaddedSortedAndUnique() {
        GeneratedDataset dataset = generator.generate(50_000, 2_000, 99L);
        List<String> orderIds = dataset.orders().stream().map(DeliveryOrder::getOrderId).toList();
        List<String> vehicleIds = dataset.vehicles().stream().map(Vehicle::getVehicleId).toList();

        assertThat(orderIds).first().isEqualTo("ORD000001");
        assertThat(orderIds).last().isEqualTo("ORD050000");
        assertThat(orderIds).allMatch(id -> id.matches("ORD\\d{6}")).isSorted().doesNotHaveDuplicates();
        assertThat(vehicleIds).first().isEqualTo("VEH0001");
        assertThat(vehicleIds).last().isEqualTo("VEH2000");
        assertThat(vehicleIds).allMatch(id -> id.matches("VEH\\d{4}")).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void idFormatters() {
        assertThat(TestDataGenerator.orderId(7)).isEqualTo("ORD000007");
        assertThat(TestDataGenerator.orderId(123_456)).isEqualTo("ORD123456");
        assertThat(TestDataGenerator.vehicleId(42)).isEqualTo("VEH0042");
        assertThat(TestDataGenerator.vehicleId(12_345)).isEqualTo("VEH12345");
    }

    @Test
    void priorityMixIsRoughly30_40_30() {
        List<DeliveryOrder> orders = generator.generate(10_000, 1, 2024L).orders();
        Map<Priority, Long> counts = new EnumMap<>(Priority.class);
        orders.forEach(o -> counts.merge(o.getPriority(), 1L, Long::sum));

        // Binomial standard deviation for n=10,000 is about 46-49; 250 is more than 5 sigma.
        assertThat(counts.get(Priority.HIGH)).isBetween(2_750L, 3_250L);
        assertThat(counts.get(Priority.MEDIUM)).isBetween(3_750L, 4_250L);
        assertThat(counts.get(Priority.LOW)).isBetween(2_750L, 3_250L);
    }

    @Test
    void weightsAndCapacitiesStayInRangeAndCoverIt() {
        GeneratedDataset dataset = generator.generate(10_000, 2_000, 5L);
        Set<Integer> weights = dataset.orders().stream().map(DeliveryOrder::getPackageWeight)
                .collect(Collectors.toSet());
        Set<Integer> capacities = dataset.vehicles().stream().map(Vehicle::getCapacity)
                .collect(Collectors.toSet());

        assertThat(weights).allMatch(w -> w >= 1 && w <= 50).contains(1, 50);
        assertThat(capacities).allMatch(c -> c >= 100 && c <= 500).contains(100, 500);
    }

    @Test
    void ordersLieWithin15KmOfTheirNamedCityAndHaveValidCoordinates() {
        List<DeliveryOrder> orders = generator.generate(10_000, 0, 11L).orders();
        Set<IndianCity> citiesUsed = new HashSet<>();

        for (DeliveryOrder order : orders) {
            assertThat(order.getLatitude()).isBetween(-90.0, 90.0);
            assertThat(order.getLongitude()).isBetween(-180.0, 180.0);
            assertThat(order.getAddress()).matches("Near [A-Za-z]+, India");
            IndianCity city = CITY_BY_ADDRESS.get(order.getAddress());
            assertThat(city).as("address %s names a known city", order.getAddress()).isNotNull();
            citiesUsed.add(city);
            assertThat(haversineKm(order.getLatitude(), order.getLongitude(), city.latitude(), city.longitude()))
                    .isLessThanOrEqualTo(MAX_DISTANCE_FROM_CITY_KM);
            assertThat(nearestCityDistanceKm(order.getLatitude(), order.getLongitude()))
                    .isLessThanOrEqualTo(MAX_DISTANCE_FROM_CITY_KM);
        }
        assertThat(citiesUsed).hasSize(IndianCity.values().length);
    }

    @Test
    void offsetsUseMostOfThe15KmRadius() {
        double maxSeen = generator.generate(5_000, 0, 3L).orders().stream()
                .mapToDouble(o -> {
                    IndianCity city = CITY_BY_ADDRESS.get(o.getAddress());
                    return haversineKm(o.getLatitude(), o.getLongitude(), city.latitude(), city.longitude());
                })
                .max().orElseThrow();
        assertThat(maxSeen).isGreaterThan(14.0);
    }

    @Test
    void vehiclesLieWithin15KmOfTheirNamedCity() {
        for (Vehicle vehicle : generator.generate(0, 2_000, 13L).vehicles()) {
            IndianCity city = CITY_BY_ADDRESS.get(vehicle.getCurrentAddress());
            assertThat(city).isNotNull();
            assertThat(haversineKm(vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude(),
                    city.latitude(), city.longitude())).isLessThanOrEqualTo(MAX_DISTANCE_FROM_CITY_KM);
        }
    }

    @Test
    void coordinatesAreRoundedToSixDecimals() {
        for (DeliveryOrder order : generator.generate(200, 0, 17L).orders()) {
            assertThat(order.getLatitude() * 1e6).isCloseTo(Math.rint(order.getLatitude() * 1e6), within(1e-6));
            assertThat(order.getLongitude() * 1e6).isCloseTo(Math.rint(order.getLongitude() * 1e6), within(1e-6));
        }
    }

    @Test
    void generatedEntitiesAreNew() {
        GeneratedDataset dataset = generator.generate(3, 3, 1L);

        assertThat(dataset.orders()).allMatch(DeliveryOrder::isNew);
        assertThat(dataset.vehicles()).allMatch(Vehicle::isNew);
    }

    @Test
    void latitudeIsClampedAndLongitudeWrapped() {
        assertThat(TestDataGenerator.clampLatitude(90.5)).isEqualTo(90.0);
        assertThat(TestDataGenerator.clampLatitude(-91)).isEqualTo(-90.0);
        assertThat(TestDataGenerator.clampLatitude(12.5)).isEqualTo(12.5);
        assertThat(TestDataGenerator.normalizeLongitude(77.2)).isEqualTo(77.2);
        assertThat(TestDataGenerator.normalizeLongitude(180.0)).isEqualTo(180.0);
        assertThat(TestDataGenerator.normalizeLongitude(181.0)).isCloseTo(-179.0, within(1e-9));
        assertThat(TestDataGenerator.normalizeLongitude(-181.0)).isCloseTo(179.0, within(1e-9));
    }

    @Test
    void cityTableHasThirtyCitiesInsideIndia() {
        assertThat(IndianCity.values()).hasSize(30);
        for (IndianCity city : IndianCity.values()) {
            assertThat(city.latitude()).isBetween(6.0, 37.0);
            assertThat(city.longitude()).isBetween(68.0, 98.0);
            assertThat(city.displayName()).isNotBlank();
        }
        assertThat(IndianCity.DELHI.latitude()).isEqualTo(28.6139);
        assertThat(IndianCity.BANGALORE.longitude()).isEqualTo(77.5946);
    }

    @Test
    void datasetListsAreImmutable() {
        GeneratedDataset dataset = generator.generate(1, 1, 1L);
        assertThatThrownBy(() -> dataset.orders().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dataset.vehicles().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static double nearestCityDistanceKm(double lat, double lon) {
        return Arrays.stream(IndianCity.values())
                .mapToDouble(c -> haversineKm(lat, lon, c.latitude(), c.longitude()))
                .min().orElseThrow();
    }

    /** Local Haversine (R = 6371.0 km) so this test does not depend on the production calculator. */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    private static List<String> fingerprintOrders(List<DeliveryOrder> orders) {
        return orders.stream().map(o -> String.join("|", o.getOrderId(), Double.toString(o.getLatitude()),
                Double.toString(o.getLongitude()), o.getAddress(), Integer.toString(o.getPackageWeight()),
                o.getPriority().name())).toList();
    }

    private static List<String> fingerprintVehicles(List<Vehicle> vehicles) {
        return vehicles.stream().map(v -> String.join("|", v.getVehicleId(), Integer.toString(v.getCapacity()),
                Double.toString(v.getCurrentLatitude()), Double.toString(v.getCurrentLongitude()),
                v.getCurrentAddress())).toList();
    }
}
