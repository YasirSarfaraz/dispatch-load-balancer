package com.freightfox.dispatch.strategy;

import com.freightfox.dispatch.entity.DeliveryOrder;

import java.util.Objects;

/** An order that could not be assigned, with a reason from {@link UnassignmentReasons}. */
public record UnassignedOrder(DeliveryOrder order, String reason) {

    public UnassignedOrder {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(reason, "reason");
    }
}
