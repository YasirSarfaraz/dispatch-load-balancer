package com.freightfox.dispatch.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Body of GET /api/dispatch/plan. Every vehicle appears in dispatchPlan, used or not. */
@Schema(description = "Optimized dispatch plan")
public record DispatchPlanResponse(
        List<VehiclePlanResponse> dispatchPlan,
        @Schema(description = "Orders that could not be assigned, with a reason")
        List<UnassignedOrderResponse> unassignedOrders,
        @Schema(description = "Strategy that actually produced the plan", example = "greedy",
                allowableValues = {"greedy", "ortools"})
        String strategy) {

    public DispatchPlanResponse {
        dispatchPlan = dispatchPlan == null ? List.of() : List.copyOf(dispatchPlan);
        unassignedOrders = unassignedOrders == null ? List.of() : List.copyOf(unassignedOrders);
    }
}
