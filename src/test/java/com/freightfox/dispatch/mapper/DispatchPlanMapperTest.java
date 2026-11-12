package com.freightfox.dispatch.mapper;

import com.freightfox.dispatch.dto.response.DispatchPlanResponse;
import com.freightfox.dispatch.dto.response.OrderResponse;
import com.freightfox.dispatch.dto.response.UnassignedOrderResponse;
import com.freightfox.dispatch.dto.response.VehiclePlanResponse;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchPlanMapperTest {

    private final DispatchPlanMapper mapper = new DispatchPlanMapper();

    private final Vehicle veh1 = new Vehicle("VEH001", 100, 28.7041, 77.1025, "Karol Bagh");
    private final Vehicle veh2 = new Vehicle("VEH002", 80, 28.5355, 77.391, "Sector 18");
    private final DeliveryOrder ordA = new DeliveryOrder("ORD003", 28.6139, 77.209, "Connaught Place", 20, Priority.LOW);
    private final DeliveryOrder ordB = new DeliveryOrder("ORD001", 28.4595, 77.0266, "Cyber Hub", 15, Priority.HIGH);
    private final DeliveryOrder heavy = new DeliveryOrder("ORD009", 28.6, 77.2, "Far away", 500, Priority.MEDIUM);

    @ParameterizedTest
    @CsvSource({
            "14.4378, 14.44 km",
            "0.0, 0.00 km",
            "-0.0, 0.00 km",
            "5, 5.00 km",
            "0.004, 0.00 km",
            "1234.5, 1234.50 km"
    })
    void formatsDistanceWithTwoDecimalsAndUnit(double km, String expected) {
        assertThat(DispatchPlanMapper.formatDistance(km)).isEqualTo(expected);
    }

    @Test
    void formattingIgnoresTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(DispatchPlanMapper.formatDistance(14.4378)).isEqualTo("14.44 km");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void mapsRoutesInOrderStopsInVisitingOrderAndUnassignedWithReason() {
        DispatchResult result = new DispatchResult(StrategyNames.ORTOOLS,
                List.of(new VehicleRoute(veh1, List.of(ordA, ordB), 35, 27.123), VehicleRoute.empty(veh2)),
                List.of(new UnassignedOrder(heavy, UnassignmentReasons.EXCEEDS_EVERY_VEHICLE)));

        DispatchPlanResponse response = mapper.toResponse(result);

        assertThat(response.strategy()).isEqualTo("ortools");
        assertThat(response.dispatchPlan()).containsExactly(
                new VehiclePlanResponse("VEH001", 35, "27.12 km", List.of(
                        new OrderResponse("ORD003", 28.6139, 77.209, "Connaught Place", 20, Priority.LOW),
                        new OrderResponse("ORD001", 28.4595, 77.0266, "Cyber Hub", 15, Priority.HIGH))),
                new VehiclePlanResponse("VEH002", 0, "0.00 km", List.of()));
        assertThat(response.unassignedOrders()).containsExactly(new UnassignedOrderResponse(
                "ORD009", 28.6, 77.2, "Far away", 500, Priority.MEDIUM,
                "Package weight exceeds capacity of every vehicle"));
    }

    @Test
    void emptyResultMapsToEmptyLists() {
        DispatchPlanResponse response = mapper.toResponse(new DispatchResult(StrategyNames.GREEDY, null, null));

        assertThat(response.dispatchPlan()).isEmpty();
        assertThat(response.unassignedOrders()).isEmpty();
        assertThat(response.strategy()).isEqualTo("greedy");
    }
}
