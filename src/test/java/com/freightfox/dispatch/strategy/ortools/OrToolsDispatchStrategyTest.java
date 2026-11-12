package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.config.DispatchProperties;
import com.freightfox.dispatch.distance.DistanceCalculator;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;
import com.freightfox.dispatch.strategy.greedy.GreedyPriorityDispatchStrategy;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

class OrToolsDispatchStrategyTest {

    private static final Logger log = LoggerFactory.getLogger(OrToolsDispatchStrategyTest.class);

    private static final Duration SAMPLE_TIME_LIMIT = Duration.ofSeconds(1);
    private static final Duration SMALL_TIME_LIMIT = Duration.ofMillis(300);
    private static final double ORIGIN_LAT = 28.6139;
    private static final double ORIGIN_LON = 77.2090;

    private final DistanceCalculator haversine = new TestHaversine();
    private final RecordingFallbackStrategy fallback = new RecordingFallbackStrategy();
    private final OrToolsNativeLoader realLoader = new OrToolsNativeLoader();

    // ---------------------------------------------------------------- native library

    @Test
    void nativeLibraryLoadsOnThisPlatform() {
        assertThat(realLoader.ensureLoaded())
                .as("OR-Tools native library for %s/%s", System.getProperty("os.name"), System.getProperty("os.arch"))
                .isTrue();
    }

    // ---------------------------------------------------------------- sample dataset (section 9)

    @Test
    void sampleDatasetSatisfiesEveryInvariant() {
        List<DeliveryOrder> orders = SampleData.orders();
        List<Vehicle> vehicles = SampleData.vehicles();
        List<DeliveryOrder> ordersCopy = List.copyOf(orders);
        List<Vehicle> vehiclesCopy = List.copyOf(vehicles);

        long start = System.nanoTime();
        DispatchResult result = withRealGreedy(SAMPLE_TIME_LIMIT).plan(orders, vehicles);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        logPlan(result, elapsedMs);

        assertThat(fallback.calls()).isZero();
        assertThat(result.strategy()).isEqualTo(StrategyNames.ORTOOLS);
        assertCommonInvariants(result, orders, vehicles);

        List<DeliveryOrder> assigned = assignedOrders(result);
        assertThat(assigned.stream().filter(o -> o.getPriority() == Priority.HIGH)).hasSize(12);
        assertThat(assigned.stream().filter(o -> o.getPriority() == Priority.MEDIUM)).hasSize(8);
        assertThat(result.assignedCount() + result.unassignedOrders().size()).isEqualTo(30);
        assertThat(result.unassignedOrders()).isNotEmpty()
                .allSatisfy(u -> {
                    assertThat(u.order().getPriority()).isEqualTo(Priority.LOW);
                    assertThat(u.reason()).isEqualTo(UnassignmentReasons.DROPPED_BY_OPTIMIZER);
                });
        // inputs untouched
        assertThat(orders).containsExactlyElementsOf(ordersCopy);
        assertThat(vehicles).containsExactlyElementsOf(vehiclesCopy);

        DispatchResult nearest = nearestVehicleGreedy(orders, vehicles);
        log.info("Sanity check (simple nearest-vehicle greedy, no 2-opt): assigned={}, totalKm={}; OR-Tools: "
                        + "assigned={}, totalKm={}", nearest.assignedCount(),
                String.format("%.2f", nearest.totalDistanceKm()), result.assignedCount(),
                String.format("%.2f", result.totalDistanceKm()));
        assertThat(result.assignedCount()).isGreaterThanOrEqualTo(nearest.assignedCount());
    }

    // ---------------------------------------------------------------- priority penalties

    @Test
    void highIsKeptOverNearbyMediumAndLow() {
        // capacity 20: either the far HIGH (20) or the near MEDIUM (10) + LOW (10).
        Vehicle vehicle = vehicle("V1", 20, ORIGIN_LAT, ORIGIN_LON);
        DeliveryOrder high = order("H", 20, Priority.HIGH, 50);
        DeliveryOrder medium = order("M", 10, Priority.MEDIUM, 0.2);
        DeliveryOrder low = order("L", 10, Priority.LOW, 0.1);

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000).plan(List.of(low, medium, high), List.of(vehicle));

        assertThat(result.routes().get(0).stops()).containsExactly(high);
        assertThat(unassignedIds(result)).containsExactly("M", "L");
        assertThat(result.unassignedOrders()).extracting(UnassignedOrder::reason)
                .containsOnly(UnassignmentReasons.DROPPED_BY_OPTIMIZER);
    }

    @Test
    void mediumIsKeptOverSeveralNearbyLowOrders() {
        // capacity 20: one far MEDIUM (20) beats two near LOW orders (10 + 10).
        Vehicle vehicle = vehicle("V1", 20, ORIGIN_LAT, ORIGIN_LON);
        DeliveryOrder medium = order("M", 20, Priority.MEDIUM, 40);
        DeliveryOrder low1 = order("L1", 10, Priority.LOW, 0.1);
        DeliveryOrder low2 = order("L2", 10, Priority.LOW, 0.2);

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000).plan(List.of(low1, low2, medium), List.of(vehicle));

        assertThat(result.routes().get(0).stops()).containsExactly(medium);
        assertThat(unassignedIds(result)).containsExactly("L1", "L2");
    }

    @Test
    void lowIsDroppedFirstWhenCapacityIsShort() {
        // capacity 30 for 40 kg: exactly one LOW must go.
        Vehicle vehicle = vehicle("V1", 30, ORIGIN_LAT, ORIGIN_LON);
        List<DeliveryOrder> orders = List.of(
                order("H", 10, Priority.HIGH, 30),
                order("M", 10, Priority.MEDIUM, 20),
                order("L1", 10, Priority.LOW, 1),
                order("L2", 10, Priority.LOW, 2));

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000).plan(orders, List.of(vehicle));

        assertCommonInvariants(result, orders, List.of(vehicle));
        assertThat(result.routes().get(0).totalLoad()).isEqualTo(30);
        assertThat(result.unassignedOrders()).singleElement()
                .satisfies(u -> assertThat(u.order().getPriority()).isEqualTo(Priority.LOW));
    }

    @Test
    void servesFarLowOrderRatherThanDroppingItToSaveDistance() {
        Vehicle vehicle = vehicle("V1", 100, ORIGIN_LAT, ORIGIN_LON);
        DeliveryOrder farLow = order("L", 10, Priority.LOW, 500);

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000).plan(List.of(farLow), List.of(vehicle));

        assertThat(result.routes().get(0).stops()).containsExactly(farLow);
        assertThat(result.unassignedOrders()).isEmpty();
    }

    @Test
    void routesAreOpenAndInVisitingOrderWithVehiclesInInputOrder() {
        Vehicle east = vehicle("V2", 100, ORIGIN_LAT, ORIGIN_LON + 1.0);
        Vehicle west = vehicle("V1", 100, ORIGIN_LAT, ORIGIN_LON);
        // a chain going east from the west vehicle; the east vehicle is far from all of them
        DeliveryOrder a = order("A", 5, Priority.LOW, 0, 0.01);
        DeliveryOrder b = order("B", 5, Priority.LOW, 0, 0.02);
        DeliveryOrder c = order("C", 5, Priority.LOW, 0, 0.03);
        DeliveryOrder farEast = order("E", 5, Priority.LOW, 0, 1.01);

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000).plan(List.of(c, farEast, a, b), List.of(east, west));

        assertThat(result.routes()).extracting(r -> r.vehicle().getVehicleId()).containsExactly("V2", "V1");
        assertThat(result.routes().get(0).stops()).containsExactly(farEast);
        assertThat(result.routes().get(1).stops()).containsExactly(a, b, c);
        double expected = haversine.distanceKm(ORIGIN_LAT, ORIGIN_LON, a.getLatitude(), a.getLongitude())
                + haversine.distanceKm(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude())
                + haversine.distanceKm(b.getLatitude(), b.getLongitude(), c.getLatitude(), c.getLongitude());
        assertThat(result.routes().get(1).totalDistanceKm()).isCloseTo(expected, within(1e-9));
        assertThat(result.routes().get(1).totalLoad()).isEqualTo(15);
    }

    // ---------------------------------------------------------------- too heavy

    @Test
    void orderHeavierThanEveryVehicleIsReportedAndNeverSentToTheSolver() {
        Vehicle small = vehicle("V1", 50, ORIGIN_LAT, ORIGIN_LON);
        Vehicle large = vehicle("V2", 80, ORIGIN_LAT, ORIGIN_LON);
        DeliveryOrder tooHeavy = order("BIG", 81, Priority.HIGH, 1);
        DeliveryOrder fits = order("OK", 80, Priority.LOW, 1);
        List<String> modelOrderIds = new ArrayList<>();
        CvrpSolver capturing = (model, seed, limit) -> {
            model.orders().forEach(o -> modelOrderIds.add(o.getOrderId()));
            return new OrToolsCvrpSolver().solve(model, seed, limit);
        };

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000, realLoader, capturing)
                .plan(List.of(tooHeavy, fits), List.of(small, large));

        assertThat(modelOrderIds).containsExactly("OK");
        assertThat(result.strategy()).isEqualTo(StrategyNames.ORTOOLS);
        assertThat(result.unassignedOrders()).singleElement().satisfies(u -> {
            assertThat(u.order()).isEqualTo(tooHeavy);
            assertThat(u.reason()).isEqualTo(UnassignmentReasons.EXCEEDS_EVERY_VEHICLE);
        });
        assertThat(result.routes().get(1).stops()).containsExactly(fits);
    }

    @Test
    void whenEveryOrderIsTooHeavyTheSolverIsNotCalled() {
        Vehicle vehicle = vehicle("V1", 10, ORIGIN_LAT, ORIGIN_LON);
        List<DeliveryOrder> orders = List.of(order("B", 11, Priority.LOW, 1), order("A", 12, Priority.HIGH, 1));

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000, realLoader, failingSolver())
                .plan(orders, List.of(vehicle));

        assertThat(result.strategy()).isEqualTo(StrategyNames.ORTOOLS);
        assertThat(result.routes()).singleElement().isEqualTo(VehicleRoute.empty(vehicle));
        // sorted by priority, then orderId
        assertThat(unassignedIds(result)).containsExactly("A", "B");
        assertThat(fallback.calls()).isZero();
    }

    // ---------------------------------------------------------------- fallbacks

    @Test
    void aboveMaxOrdersDelegatesToFallback() {
        List<DeliveryOrder> orders = SampleData.orders().subList(0, 6);
        AtomicInteger solverCalls = new AtomicInteger();
        CvrpSolver counting = (model, seed, limit) -> {
            solverCalls.incrementAndGet();
            return Optional.empty();
        };

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 5, realLoader, counting).plan(orders, SampleData.vehicles());

        assertThat(result.strategy()).isEqualTo(StrategyNames.GREEDY);
        assertThat(fallback.calls()).isEqualTo(1);
        assertThat(solverCalls).hasValue(0);
    }

    @Test
    void exactlyMaxOrdersStillUsesOrTools() {
        List<DeliveryOrder> orders = SampleData.orders().subList(0, 5);

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 5).plan(orders, SampleData.vehicles());

        assertThat(result.strategy()).isEqualTo(StrategyNames.ORTOOLS);
        assertThat(result.assignedCount()).isEqualTo(5);
    }

    @Test
    void nativeLoadFailureFallsBackWithoutThrowing() {
        AtomicInteger loadAttempts = new AtomicInteger();
        OrToolsNativeLoader broken = new OrToolsNativeLoader(() -> {
            loadAttempts.incrementAndGet();
            throw new UnsatisfiedLinkError("simulated: no jniortools on this platform");
        });
        OrToolsDispatchStrategy strategy = strategy(SMALL_TIME_LIMIT, 1000, broken, failingSolver());

        assertThatCode(() -> {
            DispatchResult first = strategy.plan(SampleData.orders(), SampleData.vehicles());
            DispatchResult second = strategy.plan(SampleData.orders(), SampleData.vehicles());
            assertThat(first.strategy()).isEqualTo(StrategyNames.GREEDY);
            assertThat(second.strategy()).isEqualTo(StrategyNames.GREEDY);
        }).doesNotThrowAnyException();
        assertThat(fallback.calls()).isEqualTo(2);
        assertThat(loadAttempts).as("failure is cached, not retried").hasValue(1);
    }

    @Test
    void noSolutionFallsBack() {
        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000, realLoader, (model, seed, limit) -> Optional.empty())
                .plan(SampleData.orders(), SampleData.vehicles());

        assertThat(result.strategy()).isEqualTo(StrategyNames.GREEDY);
        assertThat(fallback.calls()).isEqualTo(1);
    }

    @Test
    void solverExceptionFallsBack() {
        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000, realLoader, failingSolver())
                .plan(SampleData.orders(), SampleData.vehicles());

        assertThat(result.strategy()).isEqualTo(StrategyNames.GREEDY);
        assertThat(fallback.calls()).isEqualTo(1);
    }

    @Test
    void zeroTimeLimitNeverThrows() {
        DispatchResult result = strategy(Duration.ZERO, 1000).plan(SampleData.orders(), SampleData.vehicles());

        // OR-Tools may still return its first solution, or none (then greedy runs); both are fine.
        assertThat(result.strategy()).isIn(StrategyNames.ORTOOLS, StrategyNames.GREEDY);
        assertThat(result.routes()).hasSize(5);
    }

    @Test
    void solutionOverCapacityIsRejectedAndFallsBack() {
        Vehicle vehicle = vehicle("V1", 10, ORIGIN_LAT, ORIGIN_LON);
        List<DeliveryOrder> orders = List.of(order("A", 8, Priority.HIGH, 1), order("B", 8, Priority.HIGH, 2));
        CvrpSolver overloading = (model, seed, limit) ->
                Optional.of(new CvrpSolution(List.of(List.of(0, 1)), 0, "FAKE", false));

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000, realLoader, overloading).plan(orders, List.of(vehicle));

        assertThat(result.strategy()).isEqualTo(StrategyNames.GREEDY);
    }

    @Test
    void solutionVisitingAnOrderTwiceIsRejectedAndFallsBack() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 100, ORIGIN_LAT, ORIGIN_LON), vehicle("V2", 100, 0, 0));
        List<DeliveryOrder> orders = List.of(order("A", 1, Priority.HIGH, 1));
        CvrpSolver duplicating = (model, seed, limit) ->
                Optional.of(new CvrpSolution(List.of(List.of(0), List.of(0)), 0, "FAKE", false));

        assertThat(strategy(SMALL_TIME_LIMIT, 1000, realLoader, duplicating).plan(orders, vehicles).strategy())
                .isEqualTo(StrategyNames.GREEDY);
    }

    @Test
    void solutionWithWrongRouteCountIsRejectedAndFallsBack() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 100, ORIGIN_LAT, ORIGIN_LON));
        List<DeliveryOrder> orders = List.of(order("A", 1, Priority.HIGH, 1));
        CvrpSolver wrong = (model, seed, limit) -> Optional.of(new CvrpSolution(List.of(), 0, "FAKE", false));

        assertThat(strategy(SMALL_TIME_LIMIT, 1000, realLoader, wrong).plan(orders, vehicles).strategy())
                .isEqualTo(StrategyNames.GREEDY);
    }

    // ---------------------------------------------------------------- empty input

    @Test
    void emptyOrdersGiveEmptyRoutesWithoutTheSolver() {
        List<Vehicle> vehicles = SampleData.vehicles();
        OrToolsNativeLoader mustNotLoad = new OrToolsNativeLoader(() -> {
            throw new AssertionError("native library must not be loaded for an empty plan");
        });

        DispatchResult result = strategy(SMALL_TIME_LIMIT, 1000, mustNotLoad, failingSolver()).plan(List.of(), vehicles);

        assertThat(result.strategy()).isEqualTo(StrategyNames.ORTOOLS);
        assertThat(result.routes()).hasSize(vehicles.size())
                .allSatisfy(r -> {
                    assertThat(r.stops()).isEmpty();
                    assertThat(r.totalLoad()).isZero();
                    assertThat(r.totalDistanceKm()).isZero();
                });
        assertThat(result.routes()).extracting(VehicleRoute::vehicle).containsExactlyElementsOf(vehicles);
        assertThat(result.unassignedOrders()).isEmpty();
        assertThat(fallback.calls()).isZero();
    }

    @Test
    void nameIsOrtools() {
        assertThat(strategy(SMALL_TIME_LIMIT, 1000).name()).isEqualTo(StrategyNames.ORTOOLS);
    }

    @Test
    void publicConstructorWiresTheRealSolver() {
        OrToolsDispatchStrategy strategy = new OrToolsDispatchStrategy(haversine, properties(SMALL_TIME_LIMIT, 1000),
                fallback, realLoader);

        DispatchResult result = strategy.plan(List.of(order("A", 1, Priority.HIGH, 1)),
                List.of(vehicle("V1", 10, ORIGIN_LAT, ORIGIN_LON)));

        assertThat(result.strategy()).isEqualTo(StrategyNames.ORTOOLS);
        assertThat(result.assignedCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private void assertCommonInvariants(DispatchResult result, List<DeliveryOrder> orders, List<Vehicle> vehicles) {
        assertThat(result.routes()).extracting(VehicleRoute::vehicle).containsExactlyElementsOf(vehicles);
        for (VehicleRoute route : result.routes()) {
            int load = route.stops().stream().mapToInt(DeliveryOrder::getPackageWeight).sum();
            assertThat(route.totalLoad()).isEqualTo(load);
            assertThat(route.totalLoad()).isLessThanOrEqualTo(route.vehicle().getCapacity());
            assertThat(route.totalDistanceKm()).isCloseTo(openRouteKm(route), within(1e-9));
        }
        List<String> seen = Stream.concat(assignedOrders(result).stream(),
                result.unassignedOrders().stream().map(UnassignedOrder::order)).map(DeliveryOrder::getOrderId).toList();
        assertThat(seen).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(orders.stream().map(DeliveryOrder::getOrderId).toList());
        assertThat(result.unassignedOrders()).isSortedAccordingTo(Comparator
                .comparing((UnassignedOrder u) -> u.order().getPriority().rank())
                .thenComparing(u -> u.order().getOrderId()));
    }

    private double openRouteKm(VehicleRoute route) {
        double km = 0;
        double lat = route.vehicle().getCurrentLatitude();
        double lon = route.vehicle().getCurrentLongitude();
        for (DeliveryOrder stop : route.stops()) {
            km += haversine.distanceKm(lat, lon, stop.getLatitude(), stop.getLongitude());
            lat = stop.getLatitude();
            lon = stop.getLongitude();
        }
        return km;
    }

    private static List<DeliveryOrder> assignedOrders(DispatchResult result) {
        return result.routes().stream().flatMap(r -> r.stops().stream()).toList();
    }

    private static List<String> unassignedIds(DispatchResult result) {
        return result.unassignedOrders().stream().map(u -> u.order().getOrderId()).toList();
    }

    private void logPlan(DispatchResult result, long elapsedMs) {
        log.info("OR-Tools sample plan (strategy={}, wall time {} ms):", result.strategy(), elapsedMs);
        for (VehicleRoute route : result.routes()) {
            log.info("  {} cap={} load={} km={} stops={}", route.vehicle().getVehicleId(),
                    route.vehicle().getCapacity(), route.totalLoad(), String.format("%.2f", route.totalDistanceKm()),
                    route.stops().stream().map(o -> o.getOrderId() + "/" + o.getPriority() + "/" + o.getPackageWeight())
                            .toList());
        }
        log.info("  unassigned={}", result.unassignedOrders().stream()
                .map(u -> u.order().getOrderId() + "/" + u.order().getPackageWeight()).toList());
        log.info("  total km={}, assigned={}", String.format("%.2f", result.totalDistanceKm()), result.assignedCount());
    }

    /**
     * Rough sanity reference (NOT the project's greedy): priority, weight desc, id; each order to the
     * fitting vehicle whose route end is nearest; no 2-opt.
     */
    private DispatchResult nearestVehicleGreedy(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
        List<DeliveryOrder> sorted = orders.stream().sorted(Comparator
                .comparing(DeliveryOrder::getPriority)
                .thenComparing(DeliveryOrder::getPackageWeight, Comparator.reverseOrder())
                .thenComparing(DeliveryOrder::getOrderId)).toList();
        int[] remaining = vehicles.stream().mapToInt(Vehicle::getCapacity).toArray();
        double[] endLat = vehicles.stream().mapToDouble(Vehicle::getCurrentLatitude).toArray();
        double[] endLon = vehicles.stream().mapToDouble(Vehicle::getCurrentLongitude).toArray();
        List<List<DeliveryOrder>> stops = new ArrayList<>();
        vehicles.forEach(v -> stops.add(new ArrayList<>()));
        List<UnassignedOrder> unassigned = new ArrayList<>();
        for (DeliveryOrder order : sorted) {
            int best = -1;
            double bestKm = Double.MAX_VALUE;
            for (int v = 0; v < vehicles.size(); v++) {
                double km = haversine.distanceKm(endLat[v], endLon[v], order.getLatitude(), order.getLongitude());
                if (remaining[v] >= order.getPackageWeight() && km < bestKm) {
                    best = v;
                    bestKm = km;
                }
            }
            if (best < 0) {
                unassigned.add(new UnassignedOrder(order, UnassignmentReasons.INSUFFICIENT_REMAINING_CAPACITY));
                continue;
            }
            remaining[best] -= order.getPackageWeight();
            endLat[best] = order.getLatitude();
            endLon[best] = order.getLongitude();
            stops.get(best).add(order);
        }
        return new DispatchResultAssembler(haversine).assemble(StrategyNames.GREEDY, vehicles, stops, unassigned);
    }

    private static CvrpSolver failingSolver() {
        return (model, seed, limit) -> {
            throw new IllegalStateException("simulated solver crash");
        };
    }

    /** The production wiring: the real greedy strategy as warm-start seed and fallback. */
    private OrToolsDispatchStrategy withRealGreedy(Duration timeLimit) {
        DispatchProperties properties = properties(timeLimit, 1000);
        return new OrToolsDispatchStrategy(haversine, properties,
                new GreedyPriorityDispatchStrategy(haversine, properties), realLoader, new OrToolsCvrpSolver());
    }

    private OrToolsDispatchStrategy strategy(Duration timeLimit, int maxOrders) {
        return strategy(timeLimit, maxOrders, realLoader, new OrToolsCvrpSolver());
    }

    private OrToolsDispatchStrategy strategy(Duration timeLimit, int maxOrders, OrToolsNativeLoader loader,
                                             CvrpSolver solver) {
        return new OrToolsDispatchStrategy(haversine, properties(timeLimit, maxOrders), fallback, loader, solver);
    }

    private static DispatchProperties properties(Duration timeLimit, int maxOrders) {
        return new DispatchProperties(1000, new DispatchProperties.Greedy(1000, Duration.ofMillis(25)),
                new DispatchProperties.OrTools(timeLimit, maxOrders));
    }

    private static Vehicle vehicle(String id, int capacity, double lat, double lon) {
        return new Vehicle(id, capacity, lat, lon, "Depot " + id);
    }

    /** An order {@code km} kilometres (approximately) north of the origin. */
    private static DeliveryOrder order(String id, int weight, Priority priority, double km) {
        return order(id, weight, priority, km / 111.0, 0);
    }

    private static DeliveryOrder order(String id, int weight, Priority priority, double dLat, double dLon) {
        return new DeliveryOrder(id, ORIGIN_LAT + dLat, ORIGIN_LON + dLon, "Address " + id, weight, priority);
    }
}
