package com.freightfox.dispatch.strategy.greedy;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;

import java.util.Comparator;

/** The deterministic orderings used by the greedy strategy. */
public final class DispatchOrdering {

    /**
     * Order in which orders are assigned: priority (HIGH, MEDIUM, LOW), then heavier first
     * (large packages are the hardest to fit, so they get first pick), then orderId.
     */
    public static final Comparator<DeliveryOrder> ASSIGNMENT_ORDER =
            Comparator.<DeliveryOrder>comparingInt(order -> order.getPriority().rank())
                    .thenComparing(Comparator.comparingInt(DeliveryOrder::getPackageWeight).reversed())
                    .thenComparing(DeliveryOrder::getOrderId);

    /** Order of the unassigned list in the result: priority (HIGH first), then orderId. */
    public static final Comparator<DeliveryOrder> UNASSIGNED_ORDER =
            Comparator.<DeliveryOrder>comparingInt(order -> order.getPriority().rank())
                    .thenComparing(DeliveryOrder::getOrderId);

    /** Vehicle tie-break order: smaller vehicleId wins. */
    public static final Comparator<Vehicle> VEHICLE_ID_ORDER = Comparator.comparing(Vehicle::getVehicleId);

    private DispatchOrdering() {
    }
}
