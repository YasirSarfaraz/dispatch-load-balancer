package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;

import java.util.List;

/**
 * Test-only stand-in for the greedy strategy: returns empty routes with every order unassigned
 * and counts how often it was called.
 */
final class RecordingFallbackStrategy implements DispatchStrategy {

    private int calls;

    @Override
    public String name() {
        return StrategyNames.GREEDY;
    }

    @Override
    public DispatchResult plan(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
        calls++;
        return new DispatchResult(name(),
                vehicles.stream().map(VehicleRoute::empty).toList(),
                orders.stream().map(o -> new UnassignedOrder(o, UnassignmentReasons.INSUFFICIENT_REMAINING_CAPACITY))
                        .toList());
    }

    int calls() {
        return calls;
    }
}
