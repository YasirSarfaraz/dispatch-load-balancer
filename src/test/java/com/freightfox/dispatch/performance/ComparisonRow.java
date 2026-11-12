package com.freightfox.dispatch.performance;

import com.freightfox.dispatch.entity.Priority;

import java.util.Map;

/**
 * Greedy and OR-Tools on the same dataset (the same entities loaded from the database).
 *
 * @param orders                   number of orders
 * @param vehicles                 number of vehicles
 * @param orToolsKm                OR-Tools total distance
 * @param orToolsAssigned          OR-Tools assigned orders
 * @param orToolsAssignedByPriority OR-Tools assigned orders per priority
 * @param orToolsPlanMs            OR-Tools plan time
 * @param greedyKm                 greedy total distance
 * @param greedyAssigned           greedy assigned orders
 * @param greedyAssignedByPriority greedy assigned orders per priority
 * @param greedyPlanMs             greedy plan time
 */
public record ComparisonRow(
        int orders,
        int vehicles,
        double orToolsKm,
        int orToolsAssigned,
        Map<Priority, Integer> orToolsAssignedByPriority,
        long orToolsPlanMs,
        double greedyKm,
        int greedyAssigned,
        Map<Priority, Integer> greedyAssignedByPriority,
        long greedyPlanMs) {

    public ComparisonRow {
        orToolsAssignedByPriority = Map.copyOf(orToolsAssignedByPriority);
        greedyAssignedByPriority = Map.copyOf(greedyAssignedByPriority);
    }
}
