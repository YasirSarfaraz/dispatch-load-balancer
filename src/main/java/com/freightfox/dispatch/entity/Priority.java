package com.freightfox.dispatch.entity;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Delivery priority. Declaration order IS dispatch order: HIGH is planned first, then MEDIUM,
 * then LOW. {@link #rank()} (1 = most urgent) makes that explicit for comparators.
 */
public enum Priority {
    HIGH(1),
    MEDIUM(2),
    LOW(3);

    private final int rank;

    Priority(int rank) {
        this.rank = rank;
    }

    /** 1 for HIGH, 2 for MEDIUM, 3 for LOW. Lower rank is dispatched first. */
    public int rank() {
        return rank;
    }

    /** Comma separated list of allowed values, e.g. "HIGH, MEDIUM, LOW" (used in error messages). */
    public static String allowedValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
