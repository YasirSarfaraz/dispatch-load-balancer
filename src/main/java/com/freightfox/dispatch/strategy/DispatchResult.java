package com.freightfox.dispatch.strategy;

import java.util.List;

/**
 * Output of a {@link DispatchStrategy}. Not persisted; computed on demand.
 *
 * @param strategy         name of the strategy that actually produced this result (OR-Tools may
 *                         fall back to greedy, in which case this is "greedy")
 * @param routes           one route per vehicle, in input vehicle order
 * @param unassignedOrders orders that could not be assigned, HIGH first then by orderId
 */
public record DispatchResult(String strategy, List<VehicleRoute> routes, List<UnassignedOrder> unassignedOrders) {

    public DispatchResult {
        routes = routes == null ? List.of() : List.copyOf(routes);
        unassignedOrders = unassignedOrders == null ? List.of() : List.copyOf(unassignedOrders);
    }

    /** Sum of every route's distance in km. */
    public double totalDistanceKm() {
        return routes.stream().mapToDouble(VehicleRoute::totalDistanceKm).sum();
    }

    /** Number of orders placed on a vehicle. */
    public int assignedCount() {
        return routes.stream().mapToInt(r -> r.stops().size()).sum();
    }
}
