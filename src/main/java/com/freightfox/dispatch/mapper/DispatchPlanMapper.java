package com.freightfox.dispatch.mapper;

import com.freightfox.dispatch.dto.response.DispatchPlanResponse;
import com.freightfox.dispatch.dto.response.OrderResponse;
import com.freightfox.dispatch.dto.response.UnassignedOrderResponse;
import com.freightfox.dispatch.dto.response.VehiclePlanResponse;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.VehicleRoute;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Maps a {@link DispatchResult} (internal model with entities) to the API response, so entities
 * never leak into the API. Preserves route order and each route's visiting order.
 */
@Component
public class DispatchPlanMapper {

    /** Distance format: two decimals and the unit, e.g. "14.44 km". Locale.ROOT keeps the '.' separator. */
    static final String DISTANCE_FORMAT = "%.2f km";

    public DispatchPlanResponse toResponse(DispatchResult result) {
        return new DispatchPlanResponse(
                result.routes().stream().map(this::toVehiclePlan).toList(),
                result.unassignedOrders().stream().map(this::toUnassigned).toList(),
                result.strategy());
    }

    VehiclePlanResponse toVehiclePlan(VehicleRoute route) {
        return new VehiclePlanResponse(
                route.vehicle().getVehicleId(),
                route.totalLoad(),
                formatDistance(route.totalDistanceKm()),
                route.stops().stream().map(this::toOrderResponse).toList());
    }

    OrderResponse toOrderResponse(DeliveryOrder order) {
        return new OrderResponse(order.getOrderId(), order.getLatitude(), order.getLongitude(),
                order.getAddress(), order.getPackageWeight(), order.getPriority());
    }

    UnassignedOrderResponse toUnassigned(UnassignedOrder unassigned) {
        DeliveryOrder order = unassigned.order();
        return new UnassignedOrderResponse(order.getOrderId(), order.getLatitude(), order.getLongitude(),
                order.getAddress(), order.getPackageWeight(), order.getPriority(), unassigned.reason());
    }

    /** Formats kilometres as e.g. "14.44 km"; negative zero and rounding noise print as "0.00 km". */
    public static String formatDistance(double km) {
        return String.format(Locale.ROOT, DISTANCE_FORMAT, Math.max(0.0, km));
    }
}
