package com.freightfox.dispatch.performance;

import com.freightfox.dispatch.distance.DistanceCalculator;
import com.freightfox.dispatch.dto.response.DispatchPlanResponse;
import com.freightfox.dispatch.dto.response.VehiclePlanResponse;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;
import com.freightfox.dispatch.strategy.greedy.DispatchOrdering;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertions of the {@link com.freightfox.dispatch.strategy.DispatchStrategy} contract on large
 * results. Written to stay linear (hash maps, no nested scans) so that they are cheap even for
 * 50,000 orders.
 */
public final class PlanInvariants {

    /** Relative tolerance when re-computing a route's distance from its stops. */
    private static final double RELATIVE_DISTANCE_TOLERANCE = 1e-9;
    private static final double ABSOLUTE_DISTANCE_TOLERANCE_KM = 1e-6;

    private PlanInvariants() {
    }

    /**
     * Checks: the strategy that ran; exactly one route per vehicle in input order; every route's
     * load equals the sum of its stops and never exceeds capacity; every route's distance equals
     * the open-route distance re-computed with {@code calculator}; every input order appears
     * exactly once (on a route or unassigned) and nothing else appears; unassigned reasons are
     * valid for the strategy and sorted HIGH first, then by orderId.
     */
    public static void assertContract(DispatchResult result, List<DeliveryOrder> orders, List<Vehicle> vehicles,
                                      String expectedStrategy, DistanceCalculator calculator) {
        assertThat(result.strategy()).as("strategy that actually ran").isEqualTo(expectedStrategy);
        assertRoutePerVehicle(result, vehicles);
        assertLoadsAndDistances(result, calculator);
        assertEveryOrderExactlyOnce(result, orders);
        assertUnassignedReasonsAndOrder(result, vehicles);
    }

    /**
     * Greedy guarantee (section 5): an unassigned HIGH order is heavier than every vehicle's
     * remaining capacity after only the HIGH orders were placed. HIGH orders never move between
     * vehicles after assignment, so the HIGH load of each final route is exactly what the HIGH
     * phase left. It suffices to compare with the largest such remainder.
     */
    public static void assertHighPriorityInvariant(DispatchResult result) {
        int maxRemainingAfterHigh = result.routes().stream()
                .mapToInt(route -> route.vehicle().getCapacity() - route.stops().stream()
                        .filter(order -> order.getPriority() == Priority.HIGH)
                        .mapToInt(DeliveryOrder::getPackageWeight).sum())
                .max().orElse(0);
        for (UnassignedOrder unassigned : result.unassignedOrders()) {
            if (unassigned.order().getPriority() == Priority.HIGH) {
                assertThat(unassigned.order().getPackageWeight())
                        .as("unassigned HIGH %s must exceed every vehicle's remaining capacity after HIGH placement",
                                unassigned.order().getOrderId())
                        .isGreaterThan(maxRemainingAfterHigh);
            }
        }
    }

    /** Checks the API response of DispatchService.generatePlan against the stored data. */
    public static void assertResponse(DispatchPlanResponse response, List<DeliveryOrder> orders,
                                      List<Vehicle> vehicles, String expectedStrategy) {
        assertThat(response.strategy()).as("response strategy").isEqualTo(expectedStrategy);
        assertThat(response.dispatchPlan()).as("one plan entry per vehicle").hasSize(vehicles.size());
        Map<String, Integer> capacityById = vehicles.stream()
                .collect(Collectors.toMap(Vehicle::getVehicleId, Vehicle::getCapacity));
        Set<String> seen = new HashSet<>();
        for (VehiclePlanResponse plan : response.dispatchPlan()) {
            assertThat(capacityById).as("known vehicle").containsKey(plan.vehicleId());
            assertThat(plan.totalLoad()).as("load of %s", plan.vehicleId())
                    .isLessThanOrEqualTo(capacityById.get(plan.vehicleId()));
            assertThat(plan.totalDistance()).matches("\\d+\\.\\d{2} km");
            plan.assignedOrders().forEach(order ->
                    assertThat(seen.add(order.orderId())).as("%s listed once", order.orderId()).isTrue());
        }
        response.unassignedOrders().forEach(order ->
                assertThat(seen.add(order.orderId())).as("%s listed once", order.orderId()).isTrue());
        assertThat(seen).as("every stored order in the response").hasSize(orders.size());
    }

    /** Number of orders per priority (all priorities present, possibly 0). */
    public static Map<Priority, Integer> countByPriority(Collection<DeliveryOrder> orders) {
        Map<Priority, Integer> counts = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            counts.put(priority, 0);
        }
        orders.forEach(order -> counts.merge(order.getPriority(), 1, Integer::sum));
        return counts;
    }

    /** Assigned orders per priority. */
    public static Map<Priority, Integer> assignedByPriority(DispatchResult result) {
        return countByPriority(result.routes().stream().flatMap(route -> route.stops().stream()).toList());
    }

    /** Unassigned orders per priority. */
    public static Map<Priority, Integer> unassignedByPriority(DispatchResult result) {
        return countByPriority(result.unassignedOrders().stream().map(UnassignedOrder::order).toList());
    }

    private static void assertRoutePerVehicle(DispatchResult result, List<Vehicle> vehicles) {
        assertThat(result.routes()).as("one route per vehicle").hasSize(vehicles.size());
        for (int i = 0; i < vehicles.size(); i++) {
            assertThat(result.routes().get(i).vehicle().getVehicleId()).as("route %d vehicle (input order)", i)
                    .isEqualTo(vehicles.get(i).getVehicleId());
        }
    }

    private static void assertLoadsAndDistances(DispatchResult result, DistanceCalculator calculator) {
        for (VehicleRoute route : result.routes()) {
            Vehicle vehicle = route.vehicle();
            int load = route.stops().stream().mapToInt(DeliveryOrder::getPackageWeight).sum();
            assertThat(route.totalLoad()).as("reported load of %s", vehicle.getVehicleId()).isEqualTo(load);
            assertThat(load).as("load of %s vs capacity", vehicle.getVehicleId())
                    .isLessThanOrEqualTo(vehicle.getCapacity());
            double expectedKm = openRouteKm(vehicle, route.stops(), calculator);
            double tolerance = Math.max(ABSOLUTE_DISTANCE_TOLERANCE_KM, expectedKm * RELATIVE_DISTANCE_TOLERANCE);
            assertThat(Math.abs(route.totalDistanceKm() - expectedKm))
                    .as("distance of %s re-computed from its stops", vehicle.getVehicleId())
                    .isLessThanOrEqualTo(tolerance);
        }
    }

    private static void assertEveryOrderExactlyOnce(DispatchResult result, List<DeliveryOrder> orders) {
        Map<String, Integer> occurrences = new HashMap<>(orders.size() * 2);
        result.routes().forEach(route -> route.stops()
                .forEach(order -> occurrences.merge(order.getOrderId(), 1, Integer::sum)));
        result.unassignedOrders().forEach(u -> occurrences.merge(u.order().getOrderId(), 1, Integer::sum));

        Set<String> inputIds = orders.stream().map(DeliveryOrder::getOrderId).collect(Collectors.toSet());
        assertThat(occurrences.keySet()).as("orders in the result = input orders").isEqualTo(inputIds);
        List<String> duplicates = occurrences.entrySet().stream()
                .filter(e -> e.getValue() != 1).map(Map.Entry::getKey).limit(10).toList();
        assertThat(duplicates).as("orders appearing more than once").isEmpty();
        assertThat(result.assignedCount() + result.unassignedOrders().size()).isEqualTo(orders.size());
    }

    private static void assertUnassignedReasonsAndOrder(DispatchResult result, List<Vehicle> vehicles) {
        int maxCapacity = vehicles.stream().mapToInt(Vehicle::getCapacity).max().orElse(0);
        Set<String> capacityReasons = Set.of(UnassignmentReasons.INSUFFICIENT_REMAINING_CAPACITY,
                UnassignmentReasons.DROPPED_BY_OPTIMIZER);
        for (UnassignedOrder unassigned : result.unassignedOrders()) {
            if (unassigned.order().getPackageWeight() > maxCapacity) {
                assertThat(unassigned.reason()).isEqualTo(UnassignmentReasons.EXCEEDS_EVERY_VEHICLE);
            } else {
                assertThat(unassigned.reason()).as("reason of %s", unassigned.order().getOrderId())
                        .isIn(capacityReasons);
            }
        }
        List<DeliveryOrder> unassignedOrders = result.unassignedOrders().stream().map(UnassignedOrder::order).toList();
        assertThat(unassignedOrders).as("unassigned sorted HIGH first, then orderId")
                .isSortedAccordingTo(DispatchOrdering.UNASSIGNED_ORDER);
    }

    /** Open route distance: vehicle location -> stop 1 -> ... -> last stop. */
    public static double openRouteKm(Vehicle vehicle, List<DeliveryOrder> stops, DistanceCalculator calculator) {
        double total = 0.0;
        double lat = vehicle.getCurrentLatitude();
        double lon = vehicle.getCurrentLongitude();
        for (DeliveryOrder stop : stops) {
            total += calculator.distanceKm(lat, lon, stop.getLatitude(), stop.getLongitude());
            lat = stop.getLatitude();
            lon = stop.getLongitude();
        }
        return total;
    }
}
