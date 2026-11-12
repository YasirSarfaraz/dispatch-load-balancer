package com.freightfox.dispatch.strategy.greedy;

import com.freightfox.dispatch.config.DispatchProperties;
import com.freightfox.dispatch.distance.DistanceCalculator;
import com.freightfox.dispatch.distance.HaversineTable;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Default dispatch strategy: priority-first greedy assignment by cheapest insertion at the route
 * end, followed by per-vehicle route improvement.
 *
 * <ol>
 *   <li>Sort orders by priority (HIGH, MEDIUM, LOW), then packageWeight descending, then orderId.</li>
 *   <li>Each vehicle tracks its remaining capacity and its route end (initially its location).</li>
 *   <li>Each order goes to the vehicle with enough remaining capacity whose route end is nearest
 *       (Haversine); ties go to the smaller vehicleId. That vehicle's route end moves to the order.</li>
 *   <li>Orders no vehicle can take are unassigned: {@link UnassignmentReasons#EXCEEDS_EVERY_VEHICLE}
 *       if heavier than every vehicle's capacity, otherwise
 *       {@link UnassignmentReasons#INSUFFICIENT_REMAINING_CAPACITY}.</li>
 *   <li>Each vehicle's stops are re-sequenced by nearest-neighbour and 2-opt ({@link RouteOptimizer});
 *       a change is kept only if it shortens the route.</li>
 *   <li>Route distance is the open path vehicle location -&gt; stop 1 -&gt; ... -&gt; last stop,
 *       computed with the injected {@link DistanceCalculator}.</li>
 * </ol>
 *
 * <p>Because all HIGH orders are placed before any MEDIUM or LOW order, an unassigned HIGH order
 * is heavier than every vehicle's capacity left after the HIGH orders alone.
 *
 * <p>Complexity: assignment is O(n log n + n x m) with an allocation-free inner loop; route
 * improvement is bounded per vehicle by the configured 2-opt passes and time budget.
 */
@Component(StrategyNames.GREEDY)
public class GreedyPriorityDispatchStrategy implements DispatchStrategy {

    private static final Logger log = LoggerFactory.getLogger(GreedyPriorityDispatchStrategy.class);

    private final DistanceCalculator distanceCalculator;
    private final RouteOptimizer routeOptimizer;

    public GreedyPriorityDispatchStrategy(DistanceCalculator distanceCalculator, DispatchProperties properties) {
        this.distanceCalculator = Objects.requireNonNull(distanceCalculator, "distanceCalculator");
        DispatchProperties.Greedy greedy = Objects.requireNonNull(properties, "properties").greedy();
        this.routeOptimizer = new RouteOptimizer(greedy.twoOptMaxIterations(), greedy.twoOptTimeBudget());
    }

    @Override
    public String name() {
        return StrategyNames.GREEDY;
    }

    @Override
    public DispatchResult plan(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(vehicles, "vehicles");
        long startNanos = System.nanoTime();

        List<DeliveryOrder> sortedOrders = orders.stream().sorted(DispatchOrdering.ASSIGNMENT_ORDER).toList();
        int[] vehicleRank = vehicleIdOrder(vehicles);
        int orderCount = sortedOrders.size();
        int vehicleCount = vehicles.size();

        HaversineTable points = buildPointTable(sortedOrders, vehicles, vehicleRank);
        int[] weights = sortedOrders.stream().mapToInt(DeliveryOrder::getPackageWeight).toArray();
        int[] capacities = Arrays.stream(vehicleRank).map(i -> vehicles.get(i).getCapacity()).toArray();
        int[] assignedVehicle = CapacityAssigner.assign(weights, capacities, points, orderCount);

        List<UnassignedOrder> unassigned = unassignedOrders(sortedOrders, assignedVehicle, capacities);
        int[][] stopsPerVehicle = groupStops(assignedVehicle, vehicleCount);

        VehicleRoute[] routes = new VehicleRoute[vehicleCount];
        for (int rank = 0; rank < vehicleCount; rank++) {
            Vehicle vehicle = vehicles.get(vehicleRank[rank]);
            routes[vehicleRank[rank]] = buildRoute(vehicle, orderCount + rank, stopsPerVehicle[rank],
                    points, sortedOrders);
        }

        DispatchResult result = new DispatchResult(name(), Arrays.asList(routes), unassigned);
        if (log.isDebugEnabled()) {
            log.debug("Greedy plan: {} orders, {} vehicles, {} assigned, {} unassigned, {} km in {} ms",
                    orderCount, vehicleCount, result.assignedCount(), unassigned.size(),
                    String.format("%.2f", result.totalDistanceKm()), (System.nanoTime() - startNanos) / 1_000_000);
        }
        return result;
    }

    /** Input indices of the vehicles sorted by vehicleId (position = tie-break rank). */
    private static int[] vehicleIdOrder(List<Vehicle> vehicles) {
        Comparator<Integer> byId = Comparator.comparing(i -> vehicles.get(i).getVehicleId());
        return IntStream.range(0, vehicles.size()).boxed().sorted(byId).mapToInt(Integer::intValue).toArray();
    }

    /** Points 0..n-1 are the sorted orders; point n + rank is the vehicle with that id rank. */
    private static HaversineTable buildPointTable(List<DeliveryOrder> sortedOrders, List<Vehicle> vehicles,
                                                  int[] vehicleRank) {
        int orderCount = sortedOrders.size();
        int total = orderCount + vehicleRank.length;
        double[] latitudes = new double[total];
        double[] longitudes = new double[total];
        for (int i = 0; i < orderCount; i++) {
            DeliveryOrder order = sortedOrders.get(i);
            latitudes[i] = order.getLatitude();
            longitudes[i] = order.getLongitude();
        }
        for (int rank = 0; rank < vehicleRank.length; rank++) {
            Vehicle vehicle = vehicles.get(vehicleRank[rank]);
            latitudes[orderCount + rank] = vehicle.getCurrentLatitude();
            longitudes[orderCount + rank] = vehicle.getCurrentLongitude();
        }
        return new HaversineTable(latitudes, longitudes);
    }

    private static List<UnassignedOrder> unassignedOrders(List<DeliveryOrder> sortedOrders, int[] assignedVehicle,
                                                          int[] capacities) {
        int maxCapacity = Arrays.stream(capacities).max().orElse(0);
        List<UnassignedOrder> unassigned = new ArrayList<>();
        for (int i = 0; i < assignedVehicle.length; i++) {
            if (assignedVehicle[i] == CapacityAssigner.UNASSIGNED) {
                DeliveryOrder order = sortedOrders.get(i);
                String reason = order.getPackageWeight() > maxCapacity
                        ? UnassignmentReasons.EXCEEDS_EVERY_VEHICLE
                        : UnassignmentReasons.INSUFFICIENT_REMAINING_CAPACITY;
                unassigned.add(new UnassignedOrder(order, reason));
            }
        }
        unassigned.sort(Comparator.comparing(UnassignedOrder::order, DispatchOrdering.UNASSIGNED_ORDER));
        return unassigned;
    }

    /** Order indices per vehicle rank, in assignment order. */
    private static int[][] groupStops(int[] assignedVehicle, int vehicleCount) {
        int[] counts = new int[vehicleCount];
        for (int vehicle : assignedVehicle) {
            if (vehicle != CapacityAssigner.UNASSIGNED) {
                counts[vehicle]++;
            }
        }
        int[][] stops = new int[vehicleCount][];
        for (int v = 0; v < vehicleCount; v++) {
            stops[v] = new int[counts[v]];
        }
        int[] filled = new int[vehicleCount];
        for (int order = 0; order < assignedVehicle.length; order++) {
            int vehicle = assignedVehicle[order];
            if (vehicle != CapacityAssigner.UNASSIGNED) {
                stops[vehicle][filled[vehicle]++] = order;
            }
        }
        return stops;
    }

    private VehicleRoute buildRoute(Vehicle vehicle, int startPoint, int[] assignmentOrder, HaversineTable points,
                                    List<DeliveryOrder> sortedOrders) {
        if (assignmentOrder.length == 0) {
            return VehicleRoute.empty(vehicle);
        }
        List<DeliveryOrder> baseline = toOrders(assignmentOrder, sortedOrders);
        double baselineKm = routeDistanceKm(vehicle, baseline);
        int[] improvedOrder = routeOptimizer.optimize(points, startPoint, assignmentOrder);

        List<DeliveryOrder> stops = baseline;
        double distanceKm = baselineKm;
        if (!Arrays.equals(improvedOrder, assignmentOrder)) {
            List<DeliveryOrder> improved = toOrders(improvedOrder, sortedOrders);
            double improvedKm = routeDistanceKm(vehicle, improved);
            // Guard against rounding differences between the table and the calculator.
            if (improvedKm <= baselineKm) {
                stops = improved;
                distanceKm = improvedKm;
            }
        }
        int load = stops.stream().mapToInt(DeliveryOrder::getPackageWeight).sum();
        return new VehicleRoute(vehicle, stops, load, distanceKm);
    }

    private static List<DeliveryOrder> toOrders(int[] orderIndices, List<DeliveryOrder> sortedOrders) {
        List<DeliveryOrder> result = new ArrayList<>(orderIndices.length);
        for (int index : orderIndices) {
            result.add(sortedOrders.get(index));
        }
        return result;
    }

    /** Open route distance: vehicle location -> stop 1 -> ... -> last stop. */
    private double routeDistanceKm(Vehicle vehicle, List<DeliveryOrder> stops) {
        double total = 0.0;
        double lat = vehicle.getCurrentLatitude();
        double lon = vehicle.getCurrentLongitude();
        for (DeliveryOrder stop : stops) {
            total += distanceCalculator.distanceKm(lat, lon, stop.getLatitude(), stop.getLongitude());
            lat = stop.getLatitude();
            lon = stop.getLongitude();
        }
        return total;
    }
}
