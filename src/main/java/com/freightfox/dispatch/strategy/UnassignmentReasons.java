package com.freightfox.dispatch.strategy;

/** Reasons reported for orders that could not be assigned. */
public final class UnassignmentReasons {

    /** The order is heavier than the (total) capacity of every single vehicle. */
    public static final String EXCEEDS_EVERY_VEHICLE = "Package weight exceeds capacity of every vehicle";

    /** Some vehicle could carry it when empty, but no vehicle has enough capacity left. */
    public static final String INSUFFICIENT_REMAINING_CAPACITY = "Insufficient remaining capacity across fleet";

    /** OR-Tools chose to drop the order (disjunction penalty paid). */
    public static final String DROPPED_BY_OPTIMIZER = "Dropped by optimizer: insufficient capacity";

    private UnassignmentReasons() {
    }
}
