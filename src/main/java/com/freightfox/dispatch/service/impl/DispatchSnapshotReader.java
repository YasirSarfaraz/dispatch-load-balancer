package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.VehicleService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reads the vehicles and the orders for a plan in <b>one</b> read-only transaction at REPEATABLE READ,
 * so both queries see the same database snapshot: an upload committed between the two reads can never
 * produce a plan that mixes old vehicles with new orders. (On PostgreSQL, REPEATABLE READ is snapshot
 * isolation.) Planning itself runs afterwards, outside the transaction, so a long OR-Tools search never
 * holds a database connection.
 */
@Component
public class DispatchSnapshotReader {

    private final VehicleService vehicleService;
    private final OrderService orderService;

    public DispatchSnapshotReader(VehicleService vehicleService, OrderService orderService) {
        this.vehicleService = vehicleService;
        this.orderService = orderService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DispatchSnapshot read() {
        List<Vehicle> vehicles = vehicleService.findAllVehicles();
        // Without vehicles the plan is rejected, so the orders are not needed.
        List<DeliveryOrder> orders = vehicles.isEmpty() ? List.of() : orderService.findAllOrders();
        return new DispatchSnapshot(vehicles, orders);
    }
}
