package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;

import java.util.List;
import java.util.Objects;

/**
 * The vehicles and orders a plan is computed from, read together from one consistent database snapshot.
 *
 * @param vehicles every stored vehicle
 * @param orders   every stored order (empty when there are no vehicles: the plan is rejected before it is needed)
 */
public record DispatchSnapshot(List<Vehicle> vehicles, List<DeliveryOrder> orders) {

    public DispatchSnapshot {
        vehicles = List.copyOf(Objects.requireNonNull(vehicles, "vehicles"));
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
    }
}
