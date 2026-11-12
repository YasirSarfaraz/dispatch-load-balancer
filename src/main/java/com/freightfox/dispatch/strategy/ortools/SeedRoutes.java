package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.VehicleRoute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an existing plan (the greedy plan) into warm-start routes for a {@link CvrpModel}: for
 * each model vehicle, the indices into {@link CvrpModel#orders()} of its stops in visiting order.
 *
 * <p>Vehicles are matched by vehicleId and orders by orderId, so the conversion does not depend
 * on the plan's list order. Orders that are not in the model (e.g. heavier than every vehicle) are
 * skipped, and an order is used at most once, so the seed is always structurally valid; capacity
 * feasibility is checked by OR-Tools when the seed is loaded.
 */
final class SeedRoutes {

    private SeedRoutes() {
    }

    /**
     * @param model the model the seed is for
     * @param plan  a plan for the same orders and vehicles
     * @return one route per model vehicle (possibly empty), in model vehicle order
     */
    static List<List<Integer>> fromPlan(CvrpModel model, DispatchResult plan) {
        Map<String, Integer> orderIndexById = new HashMap<>();
        for (int i = 0; i < model.numOrders(); i++) {
            orderIndexById.put(model.orders().get(i).getOrderId(), i);
        }
        Map<String, VehicleRoute> routeByVehicleId = new HashMap<>();
        for (VehicleRoute route : plan.routes()) {
            routeByVehicleId.putIfAbsent(route.vehicle().getVehicleId(), route);
        }

        boolean[] used = new boolean[model.numOrders()];
        List<List<Integer>> routes = new ArrayList<>(model.numVehicles());
        for (Vehicle vehicle : model.vehicles()) {
            List<Integer> stops = new ArrayList<>();
            VehicleRoute route = routeByVehicleId.get(vehicle.getVehicleId());
            List<DeliveryOrder> planStops = route == null ? List.of() : route.stops();
            for (DeliveryOrder stop : planStops) {
                Integer index = orderIndexById.get(stop.getOrderId());
                if (index != null && !used[index]) {
                    used[index] = true;
                    stops.add(index);
                }
            }
            routes.add(stops);
        }
        return routes;
    }
}
