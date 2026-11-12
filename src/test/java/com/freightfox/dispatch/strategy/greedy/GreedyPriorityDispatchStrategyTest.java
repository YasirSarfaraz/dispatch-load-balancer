package com.freightfox.dispatch.strategy.greedy;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.freightfox.dispatch.strategy.greedy.GreedyTestData.assertContract;
import static com.freightfox.dispatch.strategy.greedy.GreedyTestData.openRouteKm;
import static com.freightfox.dispatch.strategy.greedy.GreedyTestData.order;
import static com.freightfox.dispatch.strategy.greedy.GreedyTestData.vehicle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

class GreedyPriorityDispatchStrategyTest {

    private static final double CP_LAT = 28.6139;
    private static final double CP_LON = 77.2090;
    private static final double KB_LAT = 28.7041;
    private static final double KB_LON = 77.1025;

    private final GreedyPriorityDispatchStrategy strategy = GreedyTestData.strategy();

    @Test
    void nameIsGreedy() {
        assertThat(strategy.name()).isEqualTo(StrategyNames.GREEDY);
        assertThat(strategy.plan(List.of(), List.of(vehicle("V1", 10, 0, 0))).strategy()).isEqualTo("greedy");
    }

    @Test
    void capacityIsNeverExceeded() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 50, 0, 0));
        List<DeliveryOrder> orders = List.of(
                order("O1", 0, 0.1, 30, Priority.MEDIUM),
                order("O2", 0, 0.2, 30, Priority.MEDIUM),
                order("O3", 0, 0.3, 20, Priority.MEDIUM));

        DispatchResult result = strategy.plan(orders, vehicles);

        assertContract(result, orders, vehicles);
        assertThat(result.routes().get(0).totalLoad()).isEqualTo(50);
        assertThat(stopIds(result.routes().get(0))).containsExactlyInAnyOrder("O1", "O3");
        assertThat(result.unassignedOrders()).extracting(u -> u.order().getOrderId()).containsExactly("O2");
    }

    @Test
    void highPriorityWinsTheLastSlotEvenWhenLowIsCloser() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 10, CP_LAT, CP_LON));
        DeliveryOrder lowNearby = order("A-LOW", CP_LAT, CP_LON, 10, Priority.LOW);
        DeliveryOrder highFar = order("Z-HIGH", KB_LAT, KB_LON, 10, Priority.HIGH);

        DispatchResult result = strategy.plan(List.of(lowNearby, highFar), vehicles);

        assertThat(stopIds(result.routes().get(0))).containsExactly("Z-HIGH");
        assertThat(result.unassignedOrders()).containsExactly(
                new UnassignedOrder(lowNearby, UnassignmentReasons.INSUFFICIENT_REMAINING_CAPACITY));
    }

    @Test
    void mediumIsAssignedBeforeLow() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 10, 0, 0));
        List<DeliveryOrder> orders = List.of(
                order("O1", 0, 0, 10, Priority.LOW),
                order("O2", 0, 1, 10, Priority.MEDIUM));

        DispatchResult result = strategy.plan(orders, vehicles);

        assertThat(stopIds(result.routes().get(0))).containsExactly("O2");
        assertThat(result.unassignedOrders()).extracting(u -> u.order().getOrderId()).containsExactly("O1");
    }

    @Test
    void nearestVehicleIsChosen() {
        // VEH-A sorts first but VEH-Z is parked at the order's location.
        List<Vehicle> vehicles = List.of(vehicle("VEH-A", 100, CP_LAT, CP_LON), vehicle("VEH-Z", 100, KB_LAT, KB_LON));
        List<DeliveryOrder> orders = List.of(order("O1", KB_LAT + 0.001, KB_LON, 10, Priority.HIGH));

        DispatchResult result = strategy.plan(orders, vehicles);

        assertThat(result.routes().get(0).stops()).isEmpty();
        assertThat(stopIds(result.routes().get(1))).containsExactly("O1");
    }

    @Test
    void equalDistanceIsBrokenBySmallerVehicleId() {
        // Input order is B then A; both at the same place, so A must win the tie.
        List<Vehicle> vehicles = List.of(vehicle("VEH-B", 100, CP_LAT, CP_LON), vehicle("VEH-A", 100, CP_LAT, CP_LON));
        List<DeliveryOrder> orders = List.of(order("O1", KB_LAT, KB_LON, 10, Priority.HIGH));

        DispatchResult result = strategy.plan(orders, vehicles);

        assertThat(result.routes()).extracting(r -> r.vehicle().getVehicleId()).containsExactly("VEH-B", "VEH-A");
        assertThat(result.routes().get(0).stops()).isEmpty();
        assertThat(stopIds(result.routes().get(1))).containsExactly("O1");
    }

    @Test
    void distanceIsMeasuredFromTheRouteEndNotTheDepot() {
        // On the equator: V1 at lon 0, V2 at lon 10. O1 (heavier, first) at lon 6 -> V2 (4 vs 6 degrees).
        // O2 at lon 4: from V2's new route end (lon 6) it is 2 degrees, from V1 it is 4 -> V2.
        // Measured from V2's depot it would be 6 degrees and go to V1.
        List<Vehicle> vehicles = List.of(vehicle("V1", 100, 0, 0), vehicle("V2", 100, 0, 10));
        List<DeliveryOrder> orders = List.of(
                order("O1", 0, 6, 20, Priority.HIGH),
                order("O2", 0, 4, 10, Priority.HIGH));

        DispatchResult result = strategy.plan(orders, vehicles);

        assertThat(result.routes().get(0).stops()).isEmpty();
        assertThat(stopIds(result.routes().get(1))).containsExactly("O1", "O2");
    }

    @Test
    void orderHeavierThanEveryVehicleIsUnassignedWithExceedsReason() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 100, 0, 0), vehicle("V2", 80, 0, 1));
        DeliveryOrder heavy = order("HEAVY", 0, 0.5, 150, Priority.HIGH);
        DeliveryOrder light = order("LIGHT", 0, 0.5, 5, Priority.LOW);

        DispatchResult result = strategy.plan(List.of(heavy, light), vehicles);

        assertContract(result, List.of(heavy, light), vehicles);
        assertThat(result.unassignedOrders())
                .containsExactly(new UnassignedOrder(heavy, UnassignmentReasons.EXCEEDS_EVERY_VEHICLE));
        assertThat(result.assignedCount()).isEqualTo(1);
    }

    @Test
    void orderEqualToLargestCapacityFitsAnEmptyVehicle() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 100, 0, 0));
        List<DeliveryOrder> orders = List.of(order("O1", 0, 1, 100, Priority.LOW));

        DispatchResult result = strategy.plan(orders, vehicles);

        assertThat(result.unassignedOrders()).isEmpty();
        assertThat(result.routes().get(0).totalLoad()).isEqualTo(100);
    }

    @Test
    void orderThatFitsAnEmptyVehicleButNotTheRemainderGetsInsufficientReason() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 100, 0, 0), vehicle("V2", 70, 0, 2));
        DeliveryOrder first = order("O1", 0, 0.1, 60, Priority.HIGH);
        DeliveryOrder second = order("O2", 0, 2.1, 60, Priority.HIGH);
        DeliveryOrder third = order("O3", 0, 1.0, 50, Priority.MEDIUM);

        DispatchResult result = strategy.plan(List.of(first, second, third), vehicles);

        assertThat(result.unassignedOrders())
                .containsExactly(new UnassignedOrder(third, UnassignmentReasons.INSUFFICIENT_REMAINING_CAPACITY));
    }

    @Test
    void unassignedOrdersAreSortedByPriorityThenOrderId() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 10, 0, 0));
        List<DeliveryOrder> orders = List.of(
                order("C", 0, 0, 50, Priority.LOW),
                order("B", 0, 0, 20, Priority.HIGH),
                order("A", 0, 0, 30, Priority.LOW),
                order("D", 0, 0, 40, Priority.MEDIUM),
                order("E", 0, 0, 60, Priority.HIGH));

        DispatchResult result = strategy.plan(orders, vehicles);

        assertThat(result.unassignedOrders()).extracting(u -> u.order().getOrderId())
                .containsExactly("B", "E", "D", "A", "C");
        assertThat(result.unassignedOrders()).extracting(UnassignedOrder::reason)
                .containsOnly(UnassignmentReasons.EXCEEDS_EVERY_VEHICLE);
    }

    @Test
    void heavierOrderIsAssignedFirstWithinTheSamePriority() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 50, 0, 0));
        List<DeliveryOrder> orders = List.of(
                order("A-LIGHT", 0, 0.1, 20, Priority.MEDIUM),
                order("B-HEAVY", 0, 0.2, 40, Priority.MEDIUM));

        DispatchResult result = strategy.plan(orders, vehicles);

        assertThat(stopIds(result.routes().get(0))).containsExactly("B-HEAVY");
        assertThat(result.unassignedOrders()).extracting(u -> u.order().getOrderId()).containsExactly("A-LIGHT");
    }

    @Test
    void emptyOrdersGiveEmptyRoutesForEveryVehicle() {
        List<Vehicle> vehicles = List.of(vehicle("V2", 100, 0, 0), vehicle("V1", 50, 1, 1));

        DispatchResult result = strategy.plan(List.of(), vehicles);

        assertThat(result.routes()).containsExactly(VehicleRoute.empty(vehicles.get(0)), VehicleRoute.empty(vehicles.get(1)));
        assertThat(result.unassignedOrders()).isEmpty();
        assertThat(result.totalDistanceKm()).isZero();
    }

    @Test
    void noVehiclesLeavesEveryOrderUnassigned() {
        List<DeliveryOrder> orders = List.of(order("O1", 0, 0, 5, Priority.HIGH));

        DispatchResult result = strategy.plan(orders, List.of());

        assertThat(result.routes()).isEmpty();
        assertThat(result.unassignedOrders()).extracting(UnassignedOrder::reason)
                .containsExactly(UnassignmentReasons.EXCEEDS_EVERY_VEHICLE);
    }

    @Test
    void rejectsNullInputs() {
        assertThatNullPointerException().isThrownBy(() -> strategy.plan(null, List.of()));
        assertThatNullPointerException().isThrownBy(() -> strategy.plan(List.of(), null));
    }

    @Test
    void singleVehicleTakesEverythingThatFitsInVisitingOrder() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 1000, 0, 0));
        List<DeliveryOrder> orders = List.of(
                order("O1", 0, 3, 10, Priority.LOW),
                order("O2", 0, 1, 10, Priority.HIGH),
                order("O3", 0, 2, 10, Priority.MEDIUM));

        DispatchResult result = strategy.plan(orders, vehicles);

        assertContract(result, orders, vehicles);
        VehicleRoute route = result.routes().get(0);
        assertThat(stopIds(route)).containsExactly("O2", "O3", "O1");
        assertThat(route.totalLoad()).isEqualTo(30);
        assertThat(route.totalDistanceKm()).isCloseTo(GreedyTestData.HAVERSINE.distanceKm(0, 0, 0, 3), within(1e-9));
    }

    @Test
    void routeImprovementFixesACrossingAndNeverIncreasesDistance() {
        // Assignment order by weight is lon 1, 3, 2, 4 (zig-zag, 6 degrees); the best open path is 1, 2, 3, 4.
        Vehicle vehicle = vehicle("V1", 1000, 0, 0);
        List<DeliveryOrder> orders = List.of(
                order("O1", 0, 1, 40, Priority.MEDIUM),
                order("O3", 0, 3, 30, Priority.MEDIUM),
                order("O2", 0, 2, 20, Priority.MEDIUM),
                order("O4", 0, 4, 10, Priority.MEDIUM));
        double assignmentOrderKm = openRouteKm(vehicle, orders);

        DispatchResult result = strategy.plan(orders, List.of(vehicle));

        VehicleRoute route = result.routes().get(0);
        assertThat(stopIds(route)).containsExactly("O1", "O2", "O3", "O4");
        assertThat(route.totalDistanceKm()).isLessThan(assignmentOrderKm)
                .isCloseTo(GreedyTestData.HAVERSINE.distanceKm(0, 0, 0, 4), within(1e-9));
    }

    @Test
    void twoOptCanBeDisabledWithAZeroBudgetAndResultStaysValid() {
        GreedyPriorityDispatchStrategy noTwoOpt = new GreedyPriorityDispatchStrategy(GreedyTestData.HAVERSINE,
                GreedyTestData.properties(1, Duration.ZERO));
        Random random = new Random(3);
        List<DeliveryOrder> orders = GreedyTestData.randomOrders(random, 300);
        List<Vehicle> vehicles = GreedyTestData.randomVehicles(random, 10, 100, 500);

        DispatchResult withoutTwoOpt = noTwoOpt.plan(orders, vehicles);
        DispatchResult withTwoOpt = strategy.plan(orders, vehicles);

        assertContract(withoutTwoOpt, orders, vehicles);
        assertThat(withTwoOpt.totalDistanceKm()).isLessThanOrEqualTo(withoutTwoOpt.totalDistanceKm() + 1e-9);
        assertThat(withTwoOpt.unassignedOrders()).isEqualTo(withoutTwoOpt.unassignedOrders());
    }

    @Test
    void routesFollowInputVehicleOrderAndUnusedVehiclesAreEmpty() {
        List<Vehicle> vehicles = List.of(
                vehicle("VEH-C", 100, 0, 0),
                vehicle("VEH-A", 100, 10, 10),
                vehicle("VEH-B", 100, 20, 20));
        List<DeliveryOrder> orders = List.of(order("O1", 20, 20.01, 10, Priority.LOW));

        DispatchResult result = strategy.plan(orders, vehicles);

        assertContract(result, orders, vehicles);
        assertThat(result.routes()).extracting(r -> r.vehicle().getVehicleId()).containsExactly("VEH-C", "VEH-A", "VEH-B");
        assertThat(result.routes().get(0)).isEqualTo(VehicleRoute.empty(vehicles.get(0)));
        assertThat(result.routes().get(1)).isEqualTo(VehicleRoute.empty(vehicles.get(1)));
        assertThat(stopIds(result.routes().get(2))).containsExactly("O1");
    }

    @Test
    void outputIsDeterministicForRepeatedAndShuffledInput() {
        Random random = new Random(42);
        List<DeliveryOrder> orders = GreedyTestData.randomOrders(random, 500);
        List<Vehicle> vehicles = GreedyTestData.randomVehicles(random, 20, 100, 400);

        DispatchResult first = strategy.plan(orders, vehicles);
        DispatchResult second = strategy.plan(orders, vehicles);
        List<DeliveryOrder> shuffledOrders = new ArrayList<>(orders);
        Collections.shuffle(shuffledOrders, new Random(1));
        DispatchResult shuffled = strategy.plan(shuffledOrders, vehicles);

        assertContract(first, orders, vehicles);
        assertThat(second).isEqualTo(first);
        assertThat(shuffled).isEqualTo(first);
        assertThat(stopIdsPerRoute(shuffled)).isEqualTo(stopIdsPerRoute(first));
    }

    @Test
    void shuffledVehiclesGiveTheSameRoutePerVehicle() {
        Random random = new Random(43);
        List<DeliveryOrder> orders = GreedyTestData.randomOrders(random, 400);
        List<Vehicle> vehicles = GreedyTestData.randomVehicles(random, 15, 100, 400);
        List<Vehicle> shuffledVehicles = new ArrayList<>(vehicles);
        Collections.shuffle(shuffledVehicles, new Random(2));

        DispatchResult original = strategy.plan(orders, vehicles);
        DispatchResult shuffled = strategy.plan(orders, shuffledVehicles);

        assertContract(shuffled, orders, shuffledVehicles);
        assertThat(routesById(shuffled)).isEqualTo(routesById(original));
        assertThat(shuffled.unassignedOrders()).isEqualTo(original.unassignedOrders());
    }

    @Test
    void inputsAreNotMutated() {
        Random random = new Random(5);
        List<DeliveryOrder> orders = new ArrayList<>(GreedyTestData.randomOrders(random, 200));
        List<Vehicle> vehicles = new ArrayList<>(GreedyTestData.randomVehicles(random, 8, 100, 300));
        List<String> orderSnapshot = orders.stream().map(GreedyPriorityDispatchStrategyTest::describe).toList();
        List<String> vehicleSnapshot = vehicles.stream().map(GreedyPriorityDispatchStrategyTest::describe).toList();

        strategy.plan(Collections.unmodifiableList(orders), Collections.unmodifiableList(vehicles));

        assertThat(orders.stream().map(GreedyPriorityDispatchStrategyTest::describe).toList()).isEqualTo(orderSnapshot);
        assertThat(vehicles.stream().map(GreedyPriorityDispatchStrategyTest::describe).toList()).isEqualTo(vehicleSnapshot);
    }

    @Test
    void moderateScalePlansQuickly() {
        Random random = new Random(99);
        List<DeliveryOrder> orders = GreedyTestData.randomOrders(random, 10_000);
        List<Vehicle> vehicles = GreedyTestData.randomVehicles(random, 200, 100, 500);

        long start = System.nanoTime();
        DispatchResult result = strategy.plan(orders, vehicles);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertContract(result, orders, vehicles);
        assertThat(elapsedMs).as("10,000 x 200 plan time in ms").isLessThan(5_000);
    }

    private static List<String> stopIds(VehicleRoute route) {
        return route.stops().stream().map(DeliveryOrder::getOrderId).toList();
    }

    private static List<List<String>> stopIdsPerRoute(DispatchResult result) {
        return result.routes().stream().map(GreedyPriorityDispatchStrategyTest::stopIds).toList();
    }

    private static Map<String, VehicleRoute> routesById(DispatchResult result) {
        return result.routes().stream()
                .collect(Collectors.toMap(r -> r.vehicle().getVehicleId(), Function.identity()));
    }

    private static String describe(DeliveryOrder o) {
        return o.getOrderId() + o.getLatitude() + o.getLongitude() + o.getAddress() + o.getPackageWeight() + o.getPriority();
    }

    private static String describe(Vehicle v) {
        return v.getVehicleId() + v.getCapacity() + v.getCurrentLatitude() + v.getCurrentLongitude() + v.getCurrentAddress();
    }
}
