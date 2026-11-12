package com.freightfox.dispatch.strategy;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;

import java.util.List;
import java.util.Objects;

/**
 * The planned route of one vehicle.
 *
 * @param vehicle         the vehicle
 * @param stops           assigned orders in visiting order
 * @param totalLoad       sum of packageWeight of the stops (never above vehicle capacity)
 * @param totalDistanceKm open route distance in km: vehicle location -> stop1 -> ... -> last stop
 */
public record VehicleRoute(Vehicle vehicle, List<DeliveryOrder> stops, int totalLoad, double totalDistanceKm) {

    public VehicleRoute {
        Objects.requireNonNull(vehicle, "vehicle");
        stops = stops == null ? List.of() : List.copyOf(stops);
    }

    public static VehicleRoute empty(Vehicle vehicle) {
        return new VehicleRoute(vehicle, List.of(), 0, 0.0);
    }
}
