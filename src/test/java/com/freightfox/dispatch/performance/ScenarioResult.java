package com.freightfox.dispatch.performance;

import com.freightfox.dispatch.entity.Priority;

import java.util.Map;

/**
 * Measurements of one performance scenario.
 *
 * @param strategy            strategy that actually ran (from the result, not the request)
 * @param orders              number of orders
 * @param vehicles            number of vehicles
 * @param totalWeight         sum of all package weights
 * @param totalCapacity       sum of all vehicle capacities
 * @param upsertVehiclesMs    VehicleService.upsertVehicles wall time
 * @param upsertOrdersMs      OrderService.upsertOrders wall time
 * @param planMs              strategy.plan wall time on the entities loaded from the database
 * @param endToEndMs          DispatchService.generatePlan wall time (load + plan + map to DTOs)
 * @param totalDistanceKm     sum of all route distances (open routes, Haversine)
 * @param assigned            orders on a route
 * @param unassigned          orders not on any route
 * @param assignedByPriority  assigned orders per priority
 * @param unassignedByPriority unassigned orders per priority
 * @param heap                heap measurements over generate + upsert + load + plan + end-to-end
 */
public record ScenarioResult(
        String strategy,
        int orders,
        int vehicles,
        long totalWeight,
        long totalCapacity,
        long upsertVehiclesMs,
        long upsertOrdersMs,
        long planMs,
        long endToEndMs,
        double totalDistanceKm,
        int assigned,
        int unassigned,
        Map<Priority, Integer> assignedByPriority,
        Map<Priority, Integer> unassignedByPriority,
        HeapMonitor.HeapStats heap) {

    public ScenarioResult {
        assignedByPriority = Map.copyOf(assignedByPriority);
        unassignedByPriority = Map.copyOf(unassignedByPriority);
    }

    /** Total upsert time (vehicles + orders). */
    public long upsertMs() {
        return upsertVehiclesMs + upsertOrdersMs;
    }
}
