package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.VehicleRoute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeedRoutesTest {

    private final CvrpModelBuilder builder = new CvrpModelBuilder(new TestHaversine());

    private final Vehicle v1 = new Vehicle("V1", 100, 28.70, 77.10, "KB");
    private final Vehicle v2 = new Vehicle("V2", 100, 28.53, 77.39, "N18");
    private final DeliveryOrder a = order("A");
    private final DeliveryOrder b = order("B");
    private final DeliveryOrder c = order("C");

    @Test
    void routesAreMatchedByVehicleIdAndOrderIdInVisitingOrder() {
        CvrpModel model = builder.build(List.of(a, b, c), List.of(v1, v2));
        // plan lists the vehicles in the other order: matching is by vehicleId, not position
        DispatchResult plan = plan(route(v2, b), route(v1, c, a));

        assertThat(SeedRoutes.fromPlan(model, plan)).containsExactly(List.of(2, 0), List.of(1));
    }

    @Test
    void ordersOutsideTheModelAndRepeatedOrdersAreSkipped() {
        CvrpModel model = builder.build(List.of(a, b), List.of(v1, v2));
        DeliveryOrder notInModel = order("BIG");

        DispatchResult plan = plan(route(v1, notInModel, a, b), route(v2, a));

        assertThat(SeedRoutes.fromPlan(model, plan)).containsExactly(List.of(0, 1), List.of());
    }

    @Test
    void vehicleMissingFromThePlanGetsAnEmptyRoute() {
        CvrpModel model = builder.build(List.of(a), List.of(v1, v2));
        Vehicle duplicateV2 = new Vehicle("V2", 50, 0, 0, "elsewhere");

        DispatchResult plan = plan(route(v2, a), route(duplicateV2));

        assertThat(SeedRoutes.fromPlan(model, plan)).containsExactly(List.of(), List.of(0));
    }

    private static DispatchResult plan(VehicleRoute... routes) {
        return new DispatchResult(StrategyNames.GREEDY, List.of(routes), List.of());
    }

    private static VehicleRoute route(Vehicle vehicle, DeliveryOrder... stops) {
        return new VehicleRoute(vehicle, List.of(stops), stops.length, 0.0);
    }

    private static DeliveryOrder order(String id) {
        return new DeliveryOrder(id, 28.61, 77.20, "Address " + id, 1, Priority.HIGH);
    }
}
