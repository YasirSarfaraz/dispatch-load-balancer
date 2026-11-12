package com.freightfox.dispatch.mapper;

import com.freightfox.dispatch.dto.request.OrderRequest;
import com.freightfox.dispatch.dto.request.OrdersRequest;
import com.freightfox.dispatch.dto.request.VehicleRequest;
import com.freightfox.dispatch.dto.request.VehiclesRequest;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMapperTest {

    private final RequestMapper mapper = new RequestMapper();

    @Test
    void mapsOrdersFieldByFieldAsNewEntities() {
        OrdersRequest request = new OrdersRequest(List.of(
                new OrderRequest("ORD001", 12.9716, 77.5946, "MG Road, Bangalore", 10, Priority.HIGH),
                new OrderRequest("ORD002", 13.0827, 80.2707, "Anna Salai, Chennai", 20, Priority.MEDIUM)));

        List<DeliveryOrder> orders = mapper.toOrders(request);

        assertThat(orders).hasSize(2);
        DeliveryOrder first = orders.get(0);
        assertThat(first.getOrderId()).isEqualTo("ORD001");
        assertThat(first.getLatitude()).isEqualTo(12.9716);
        assertThat(first.getLongitude()).isEqualTo(77.5946);
        assertThat(first.getAddress()).isEqualTo("MG Road, Bangalore");
        assertThat(first.getPackageWeight()).isEqualTo(10);
        assertThat(first.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(first.isNew()).isTrue();
        assertThat(orders.get(1).getOrderId()).isEqualTo("ORD002");
    }

    @Test
    void mapsVehiclesFieldByFieldAsNewEntities() {
        VehiclesRequest request = new VehiclesRequest(List.of(
                new VehicleRequest("VEH001", 100, 12.9716, 77.6413, "Indiranagar, Bangalore")));

        List<Vehicle> vehicles = mapper.toVehicles(request);

        assertThat(vehicles).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.getVehicleId()).isEqualTo("VEH001");
            assertThat(vehicle.getCapacity()).isEqualTo(100);
            assertThat(vehicle.getCurrentLatitude()).isEqualTo(12.9716);
            assertThat(vehicle.getCurrentLongitude()).isEqualTo(77.6413);
            assertThat(vehicle.getCurrentAddress()).isEqualTo("Indiranagar, Bangalore");
            assertThat(vehicle.isNew()).isTrue();
        });
    }
}
