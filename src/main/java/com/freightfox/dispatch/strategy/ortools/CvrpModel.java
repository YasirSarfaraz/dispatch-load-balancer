package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;

import java.util.Arrays;
import java.util.List;

/**
 * Solver-independent data of one Capacitated Vehicle Routing Problem instance, built by
 * {@link CvrpModelBuilder}.
 *
 * <p>Node layout (node = index into the matrix and the demand array):
 * <pre>
 *   0 .. V-1        start node of vehicle v (its current location)
 *   V .. V+N-1      order nodes, in {@link #orders()} order
 *   V+N             ONE dummy end node shared by every vehicle (open routes): every arc into it
 *                   and out of it costs 0, so a route's cost is start -> ... -> last stop
 * </pre>
 * Arrays are shared, not copied (package-internal, read-only by convention).
 */
final class CvrpModel {

    private final List<DeliveryOrder> orders;
    private final List<Vehicle> vehicles;
    private final long[][] costMatrix;
    private final long[] demands;
    private final long[] capacities;
    private final DropPenalties penalties;

    CvrpModel(List<DeliveryOrder> orders, List<Vehicle> vehicles, long[][] costMatrix, long[] demands,
              long[] capacities, DropPenalties penalties) {
        this.orders = List.copyOf(orders);
        this.vehicles = List.copyOf(vehicles);
        this.costMatrix = costMatrix;
        this.demands = demands;
        this.capacities = capacities;
        this.penalties = penalties;
    }

    /** Orders that are part of the model (each fits at least one vehicle), in node order. */
    List<DeliveryOrder> orders() {
        return orders;
    }

    /** Vehicles in input order; vehicle {@code v} is routing vehicle {@code v}. */
    List<Vehicle> vehicles() {
        return vehicles;
    }

    int numVehicles() {
        return vehicles.size();
    }

    int numOrders() {
        return orders.size();
    }

    /** Vehicle starts + orders + one shared dummy end. */
    int numNodes() {
        return numVehicles() + numOrders() + 1;
    }

    int endNode() {
        return numVehicles() + numOrders();
    }

    int orderNode(int orderIndex) {
        return numVehicles() + orderIndex;
    }

    boolean isOrderNode(int node) {
        return node >= numVehicles() && node < endNode();
    }

    /** Inverse of {@link #orderNode(int)}. */
    int orderIndex(int node) {
        return node - numVehicles();
    }

    /** Start node of each vehicle (node {@code v} for vehicle {@code v}). */
    int[] startNodes() {
        int[] starts = new int[numVehicles()];
        for (int v = 0; v < starts.length; v++) {
            starts[v] = v;
        }
        return starts;
    }

    /** End node of each vehicle: the shared dummy end. */
    int[] endNodes() {
        int[] ends = new int[numVehicles()];
        Arrays.fill(ends, endNode());
        return ends;
    }

    /** Arc cost between two nodes in {@link DropPenalties#costUnitMetres()} units. */
    long cost(int fromNode, int toNode) {
        return costMatrix[fromNode][toNode];
    }

    /** packageWeight for order nodes, 0 for start and end nodes. */
    long demand(int node) {
        return demands[node];
    }

    long[] capacities() {
        return capacities;
    }

    DropPenalties penalties() {
        return penalties;
    }
}
