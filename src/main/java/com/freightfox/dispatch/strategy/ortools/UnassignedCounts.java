package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.UnassignedOrder;

import java.util.Comparator;

/**
 * Number of unassigned orders per priority in a plan, compared <em>lexicographically</em>: fewer
 * unassigned HIGH orders is better, ties are broken by MEDIUM, then by LOW. This is the same order
 * the OR-Tools drop penalties encode (see {@link DropPenalties}); it is used by
 * {@link OrToolsDispatchStrategy} to guard the solver result against the greedy plan.
 *
 * @param high   unassigned HIGH orders
 * @param medium unassigned MEDIUM orders
 * @param low    unassigned LOW orders
 */
record UnassignedCounts(int high, int medium, int low) {

    /** Fewer unassigned HIGH first, then MEDIUM, then LOW: "smaller" is better. */
    static final Comparator<UnassignedCounts> PRIORITY_ORDER = Comparator
            .comparingInt(UnassignedCounts::high)
            .thenComparingInt(UnassignedCounts::medium)
            .thenComparingInt(UnassignedCounts::low);

    /** Counts every unassigned order of the plan (dropped by the solver or too heavy). */
    static UnassignedCounts of(DispatchResult plan) {
        int high = 0;
        int medium = 0;
        int low = 0;
        for (UnassignedOrder unassigned : plan.unassignedOrders()) {
            switch (unassigned.order().getPriority()) {
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
            }
        }
        return new UnassignedCounts(high, medium, low);
    }

    /** {@code true} if this plan leaves lexicographically more orders unassigned than {@code other}. */
    boolean isWorseThan(UnassignedCounts other) {
        return PRIORITY_ORDER.compare(this, other) > 0;
    }

    @Override
    public String toString() {
        return high + "/" + medium + "/" + low;
    }
}
