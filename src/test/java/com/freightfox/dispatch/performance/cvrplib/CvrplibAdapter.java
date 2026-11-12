package com.freightfox.dispatch.performance.cvrplib;

import com.freightfox.dispatch.distance.DistanceCalculator;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.performance.cvrplib.CvrplibInstance.Node;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.VehicleRoute;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Test-only bridge between a {@link CvrplibInstance} and the production dispatch model.
 *
 * <ul>
 *   <li>Every vehicle starts at the depot with capacity Q; every customer becomes an order with
 *       weight = demand and the same priority ({@link #PRIORITY}).</li>
 *   <li>Coordinates are passed as lat = y * {@value #DEGREES_PER_UNIT}, lon = x *
 *       {@value #DEGREES_PER_UNIT}: at most 0.1 degrees from (0, 0), where Haversine is
 *       proportional to Euclidean distance to within a few parts per million. That keeps the
 *       greedy strategy's internal Haversine ranking faithful to the Euclidean geometry, while
 *       {@link #DISTANCE} gives every reported distance (and OR-Tools' matrix) in exact CVRPLIB
 *       units.</li>
 *   <li>{@link #closedCost} / {@link #openCost} evaluate routes with the CVRPLIB EUC_2D metric:
 *       Euclidean distance rounded to the nearest integer per arc.</li>
 * </ul>
 */
final class CvrplibAdapter {

    /** Degrees of latitude/longitude per CVRPLIB coordinate unit. */
    static final double DEGREES_PER_UNIT = 1e-4;
    /** All customers share one priority, so priorities play no role in the benchmark. */
    static final Priority PRIORITY = Priority.MEDIUM;
    /**
     * CVRPLIB EUC_2D distance on scaled coordinates: nint(Euclidean distance) in coordinate units.
     * The "km" of the {@link DistanceCalculator} contract are CVRPLIB units here. For integer
     * coordinates a distance is never within 1e-5 of a .5 boundary, so the scaling round-trip
     * cannot change the rounding.
     */
    static final DistanceCalculator DISTANCE = (lat1, lon1, lat2, lon2) ->
            Math.round(Math.hypot((lon2 - lon1) / DEGREES_PER_UNIT, (lat2 - lat1) / DEGREES_PER_UNIT));

    private static final String ORDER_PREFIX = "C";
    private static final String VEHICLE_PREFIX = "VEH";
    private static final String DEPOT_ADDRESS = "Depot";
    private static final String CUSTOMER_ADDRESS = "Customer ";

    private final CvrplibInstance instance;
    private final Map<String, Node> nodeByOrderId;

    CvrplibAdapter(CvrplibInstance instance) {
        this.instance = instance;
        this.nodeByOrderId = instance.customers().stream()
                .collect(Collectors.toMap(CvrplibAdapter::orderId, Function.identity()));
    }

    /** {@code fleet} identical vehicles at the depot. */
    List<Vehicle> vehicles(int fleet) {
        Node depot = instance.depot();
        List<Vehicle> vehicles = new ArrayList<>(fleet);
        for (int i = 1; i <= fleet; i++) {
            vehicles.add(new Vehicle(String.format(Locale.ROOT, "%s%03d", VEHICLE_PREFIX, i), instance.capacity(),
                    latitude(depot), longitude(depot), DEPOT_ADDRESS));
        }
        return vehicles;
    }

    /** One order per customer. */
    List<DeliveryOrder> orders() {
        return instance.customers().stream()
                .map(node -> new DeliveryOrder(orderId(node), latitude(node), longitude(node),
                        CUSTOMER_ADDRESS + node.id(), node.demand(), PRIORITY))
                .toList();
    }

    /** The routes of a dispatch result as CVRPLIB nodes (non-empty routes only). */
    List<List<Node>> routes(DispatchResult result) {
        List<List<Node>> routes = new ArrayList<>();
        for (VehicleRoute route : result.routes()) {
            if (!route.stops().isEmpty()) {
                routes.add(route.stops().stream().map(stop -> nodeByOrderId.get(stop.getOrderId())).toList());
            }
        }
        return routes;
    }

    /** The routes of a CVRPLIB solution as nodes. */
    List<List<Node>> routes(CvrplibSolution solution) {
        Map<Integer, Node> byId = instance.customers().stream()
                .collect(Collectors.toMap(Node::id, Function.identity()));
        return solution.routes().stream()
                .map(route -> route.stream().map(c -> byId.get(CvrplibSolution.nodeId(c))).toList())
                .toList();
    }

    /** CVRPLIB cost: every route is a closed tour depot -> stops -> depot. */
    long closedCost(List<List<Node>> routes) {
        return routes.stream().mapToLong(route -> routeOpenCost(route) + arc(route.get(route.size() - 1),
                instance.depot())).sum();
    }

    /** Cost without the return legs (the open-route model the production strategies optimise). */
    long openCost(List<List<Node>> routes) {
        return routes.stream().mapToLong(this::routeOpenCost).sum();
    }

    private long routeOpenCost(List<Node> route) {
        long cost = 0;
        Node previous = instance.depot();
        for (Node node : route) {
            cost += arc(previous, node);
            previous = node;
        }
        return cost;
    }

    /** nint(Euclidean distance) on the original coordinates. */
    static long arc(Node a, Node b) {
        return Math.round(Math.hypot(a.x() - b.x(), a.y() - b.y()));
    }

    private static String orderId(Node node) {
        return String.format(Locale.ROOT, "%s%03d", ORDER_PREFIX, node.id());
    }

    private static double latitude(Node node) {
        return node.y() * DEGREES_PER_UNIT;
    }

    private static double longitude(Node node) {
        return node.x() * DEGREES_PER_UNIT;
    }
}
