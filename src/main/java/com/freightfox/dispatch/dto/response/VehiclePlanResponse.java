package com.freightfox.dispatch.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** One vehicle's part of the dispatch plan. assignedOrders are in visiting order. */
@Schema(description = "A vehicle with its assigned orders in visiting order")
public record VehiclePlanResponse(
        @Schema(example = "VEH001") String vehicleId,
        @Schema(example = "10") int totalLoad,
        @Schema(description = "Open-route distance from the vehicle's location through every stop, 2 decimals",
                example = "14.44 km") String totalDistance,
        List<OrderResponse> assignedOrders) {

    public VehiclePlanResponse {
        assignedOrders = assignedOrders == null ? List.of() : List.copyOf(assignedOrders);
    }
}
