package com.freightfox.dispatch.strategy.greedy;

import com.freightfox.dispatch.config.DispatchProperties;
import com.freightfox.dispatch.distance.DistanceCalculator;
import com.freightfox.dispatch.distance.HaversineDistanceCalculator;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.VehicleRoute;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Builders, random data and shared invariant checks for the greedy strategy tests. */
final class GreedyTestData {

    static final DistanceCalculator HAVERSINE = new HaversineDistanceCalculator();

    /** Same values as application.yml. */
    static final DispatchProperties DEFAULT_PROPERTIES = properties(1000, Duration.ofMillis(25));

    private static final double[][] CITY_CENTRES = {
            {28.6139, 77.2090}, {19.0760, 72.8777}, {12.9716, 77.5946}, {13.0827, 80.2707},
            {22.5726, 88.3639}, {17.3850, 78.4867}, {18.5204, 73.8567}, {23.0225, 72.5714}};

    private GreedyTestData() {
    }

    static DispatchProperties properties(int twoOptMaxIterations, Duration twoOptTimeBudget) {
        return new DispatchProperties(1000, new DispatchProperties.Greedy(twoOptMaxIterations, twoOptTimeBudget),
                new DispatchProperties.OrTools(Duration.ofSeconds(3), 1000));
    }

    static GreedyPriorityDispatchStrategy strategy() {
        return new GreedyPriorityDispatchStrategy(HAVERSINE, DEFAULT_PROPERTIES);
    }

    static DeliveryOrder order(String id, double lat, double lon, int weight, Priority priority) {
        return new DeliveryOrder(id, lat, lon, "Address of " + id, weight, priority);
    }

    static Vehicle vehicle(String id, int capacity, double lat, double lon) {
        return new Vehicle(id, capacity, lat, lon, "Depot of " + id);
    }

    /** Random orders around a few Indian cities; weights 1-50; ~30/40/30 HIGH/MEDIUM/LOW. */
    static List<DeliveryOrder> randomOrders(Random random, int count) {
        List<DeliveryOrder> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double[] centre = CITY_CENTRES[random.nextInt(CITY_CENTRES.length)];
            int roll = random.nextInt(10);
            Priority priority = roll < 3 ? Priority.HIGH : roll < 7 ? Priority.MEDIUM : Priority.LOW;
            orders.add(order(String.format("ORD%06d", i), jitter(random, centre[0]), jitter(random, centre[1]),
                    1 + random.nextInt(50), priority));
        }
        return orders;
    }

    /** Random vehicles around the same cities; capacity between minCapacity and maxCapacity. */
    static List<Vehicle> randomVehicles(Random random, int count, int minCapacity, int maxCapacity) {
        List<Vehicle> vehicles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double[] centre = CITY_CENTRES[random.nextInt(CITY_CENTRES.length)];
            vehicles.add(vehicle(String.format("VEH%05d", i), minCapacity + random.nextInt(maxCapacity - minCapacity + 1),
                    jitter(random, centre[0]), jitter(random, centre[1])));
        }
        return vehicles;
    }

    private static double jitter(Random random, double value) {
        return value + (random.nextDouble() - 0.5) * 0.25;
    }

    /** Open route distance recomputed independently of the strategy. */
    static double openRouteKm(Vehicle vehicle, List<DeliveryOrder> stops) {
        double total = 0;
        double lat = vehicle.getCurrentLatitude();
        double lon = vehicle.getCurrentLongitude();
        for (DeliveryOrder stop : stops) {
            total += HAVERSINE.distanceKm(lat, lon, stop.getLatitude(), stop.getLongitude());
            lat = stop.getLatitude();
            lon = stop.getLongitude();
        }
        return total;
    }

    /** The structural contract of DispatchStrategy that holds for every input. */
    static void assertContract(DispatchResult result, List<DeliveryOrder> orders, List<Vehicle> vehicles) {
        assertThat(result.strategy()).isEqualTo("greedy");
        assertThat(result.routes()).extracting(VehicleRoute::vehicle).containsExactlyElementsOf(vehicles);

        List<String> seen = new ArrayList<>();
        for (VehicleRoute route : result.routes()) {
            int load = route.stops().stream().mapToInt(DeliveryOrder::getPackageWeight).sum();
            assertThat(route.totalLoad()).as("load of %s", route.vehicle()).isEqualTo(load);
            assertThat(load).as("capacity of %s", route.vehicle()).isLessThanOrEqualTo(route.vehicle().getCapacity());
            assertThat(route.totalDistanceKm()).as("distance of %s", route.vehicle())
                    .isCloseTo(openRouteKm(route.vehicle(), route.stops()), within(1e-9));
            if (route.stops().isEmpty()) {
                assertThat(route.totalDistanceKm()).isZero();
            }
            route.stops().forEach(stop -> seen.add(stop.getOrderId()));
        }
        result.unassignedOrders().forEach(u -> seen.add(u.order().getOrderId()));
        assertThat(seen).as("every order exactly once")
                .containsExactlyInAnyOrderElementsOf(orders.stream().map(DeliveryOrder::getOrderId).toList());

        List<DeliveryOrder> unassigned = result.unassignedOrders().stream().map(UnassignedOrder::order).toList();
        assertThat(unassigned).isSortedAccordingTo(DispatchOrdering.UNASSIGNED_ORDER);
    }
}
