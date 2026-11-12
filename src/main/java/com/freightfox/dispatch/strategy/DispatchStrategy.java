package com.freightfox.dispatch.strategy;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;

import java.util.List;

/**
 * Strategy pattern: an algorithm that assigns orders to vehicles and sequences each route.
 * Implementations are Spring beans named after {@link #name()} and are looked up through the
 * DispatchStrategyRegistry.
 *
 * <p>Contract for every implementation:
 * <ul>
 *   <li>Must not mutate the input lists or entities.</li>
 *   <li>Returns exactly one {@link VehicleRoute} per input vehicle, in the input vehicle order
 *       (unused vehicles get an empty route).</li>
 *   <li>Each order appears exactly once: either in one route's stops or in unassignedOrders.</li>
 *   <li>No route's totalLoad exceeds its vehicle's capacity.</li>
 *   <li>Route stops are in visiting order; totalDistanceKm is the open route
 *       start -> stop1 -> ... -> last stop (no return trip), in km, Haversine.</li>
 *   <li>unassignedOrders are sorted by priority (HIGH first), then orderId.</li>
 *   <li>Empty orders list: every vehicle gets an empty route. The caller guarantees
 *       vehicles is non-empty.</li>
 *   <li>Deterministic: the same input gives the same output.</li>
 * </ul>
 */
public interface DispatchStrategy {

    /** Unique strategy name, one of {@link StrategyNames}. */
    String name();

    DispatchResult plan(List<DeliveryOrder> orders, List<Vehicle> vehicles);
}
