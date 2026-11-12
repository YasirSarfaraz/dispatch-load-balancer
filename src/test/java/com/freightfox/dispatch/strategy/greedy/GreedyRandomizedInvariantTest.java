package com.freightfox.dispatch.strategy.greedy;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;

import static com.freightfox.dispatch.strategy.greedy.GreedyTestData.assertContract;
import static com.freightfox.dispatch.strategy.greedy.GreedyTestData.openRouteKm;
import static org.assertj.core.api.Assertions.assertThat;

/** Invariants of the greedy strategy on randomized fleets and order books. */
class GreedyRandomizedInvariantTest {

    private static final double EPSILON_KM = 1e-9;

    private final GreedyPriorityDispatchStrategy strategy = GreedyTestData.strategy();

    static LongStream seeds() {
        return LongStream.range(0, 200);
    }

    /** Random sizes; fleets range from far too small to roomy, so every branch is exercised. */
    private static Scenario scenario(long seed) {
        Random random = new Random(seed);
        int orderCount = random.nextInt(150);
        int vehicleCount = 1 + random.nextInt(12);
        int maxCapacity = 20 + random.nextInt(300);
        List<DeliveryOrder> orders = GreedyTestData.randomOrders(random, orderCount);
        List<Vehicle> vehicles = GreedyTestData.randomVehicles(random, vehicleCount, 10, maxCapacity);
        return new Scenario(orders, vehicles);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void unassignedHighOrderExceedsEveryRemainingCapacityAfterHighOrders(long seed) {
        Scenario s = scenario(seed);

        DispatchResult result = strategy.plan(s.orders(), s.vehicles());

        for (UnassignedOrder unassigned : result.unassignedOrders()) {
            if (unassigned.order().getPriority() != Priority.HIGH) {
                continue;
            }
            for (VehicleRoute route : result.routes()) {
                int highLoad = route.stops().stream()
                        .filter(o -> o.getPriority() == Priority.HIGH)
                        .mapToInt(DeliveryOrder::getPackageWeight).sum();
                int remainingAfterHigh = route.vehicle().getCapacity() - highLoad;
                assertThat(unassigned.order().getPackageWeight())
                        .as("seed %d: unassigned HIGH %s vs %s", seed, unassigned.order(), route.vehicle())
                        .isGreaterThan(remainingAfterHigh);
            }
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void capacityIsNeverExceededAndContractHolds(long seed) {
        Scenario s = scenario(seed);

        DispatchResult result = strategy.plan(s.orders(), s.vehicles());

        assertContract(result, s.orders(), s.vehicles());
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void unassignedReasonsMatchFleetCapacity(long seed) {
        Scenario s = scenario(seed);
        int maxCapacity = s.vehicles().stream().mapToInt(Vehicle::getCapacity).max().orElseThrow();

        DispatchResult result = strategy.plan(s.orders(), s.vehicles());

        for (UnassignedOrder unassigned : result.unassignedOrders()) {
            String expected = unassigned.order().getPackageWeight() > maxCapacity
                    ? UnassignmentReasons.EXCEEDS_EVERY_VEHICLE
                    : UnassignmentReasons.INSUFFICIENT_REMAINING_CAPACITY;
            assertThat(unassigned.reason()).as("seed %d: %s", seed, unassigned.order()).isEqualTo(expected);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void routeImprovementNeverIncreasesDistanceOverAssignmentOrder(long seed) {
        Scenario s = scenario(seed);

        DispatchResult result = strategy.plan(s.orders(), s.vehicles());

        for (VehicleRoute route : result.routes()) {
            // Within one vehicle, stops were assigned in global assignment order.
            List<DeliveryOrder> assignmentOrder = route.stops().stream()
                    .sorted(DispatchOrdering.ASSIGNMENT_ORDER).toList();
            assertThat(route.totalDistanceKm()).as("seed %d: %s", seed, route.vehicle())
                    .isLessThanOrEqualTo(openRouteKm(route.vehicle(), assignmentOrder) + EPSILON_KM);
        }
    }

    private record Scenario(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
    }
}
