package com.freightfox.dispatch.performance;

import com.freightfox.dispatch.entity.Priority;

import java.util.Map;

/**
 * One OR-Tools run of the time-limit sensitivity check.
 *
 * @param timeLimitMs          solver time limit
 * @param strategy             strategy that actually ran
 * @param assignedByPriority   assigned orders per priority
 * @param unassignedByPriority unassigned orders per priority
 * @param totalDistanceKm      total open-route distance
 * @param planMs               plan wall time
 */
public record TimeLimitRow(
        long timeLimitMs,
        String strategy,
        Map<Priority, Integer> assignedByPriority,
        Map<Priority, Integer> unassignedByPriority,
        double totalDistanceKm,
        long planMs) {

    public TimeLimitRow {
        assignedByPriority = Map.copyOf(assignedByPriority);
        unassignedByPriority = Map.copyOf(unassignedByPriority);
    }

    /** Total assigned orders. */
    public int assigned() {
        return assignedByPriority.values().stream().mapToInt(Integer::intValue).sum();
    }
}
