package com.freightfox.dispatch.mapper;

import com.freightfox.dispatch.dto.request.OrderRequest;
import com.freightfox.dispatch.dto.request.OrdersRequest;
import com.freightfox.dispatch.dto.request.VehicleRequest;
import com.freightfox.dispatch.dto.request.VehiclesRequest;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps validated request DTOs to new (transient) entities. Expects Bean Validation to have run,
 * so required wrapper fields are non-null. IDs and addresses are stored exactly as sent.
 */
@Component
public class RequestMapper {

    public List<DeliveryOrder> toOrders(OrdersRequest request) {
        return request.orders().stream().map(this::toOrder).toList();
    }

    public DeliveryOrder toOrder(OrderRequest request) {
        return new DeliveryOrder(request.orderId(), request.latitude(), request.longitude(),
                request.address(), request.packageWeight(), request.priority());
    }

    public List<Vehicle> toVehicles(VehiclesRequest request) {
        return request.vehicles().stream().map(this::toVehicle).toList();
    }

    public Vehicle toVehicle(VehicleRequest request) {
        return new Vehicle(request.vehicleId(), request.capacity(), request.currentLatitude(),
                request.currentLongitude(), request.currentAddress());
    }
}
