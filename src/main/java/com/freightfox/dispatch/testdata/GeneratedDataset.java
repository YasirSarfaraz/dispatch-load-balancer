package com.freightfox.dispatch.testdata;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;

import java.util.List;

/**
 * Output of {@link TestDataGenerator#generate(int, int, long)}: new (not yet persisted) entities,
 * sorted by ID.
 *
 * @param orders   generated delivery orders
 * @param vehicles generated vehicles
 */
public record GeneratedDataset(List<DeliveryOrder> orders, List<Vehicle> vehicles) {

    public GeneratedDataset {
        orders = List.copyOf(orders);
        vehicles = List.copyOf(vehicles);
    }
}
