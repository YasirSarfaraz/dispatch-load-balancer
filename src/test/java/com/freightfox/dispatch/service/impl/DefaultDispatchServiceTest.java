package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.dto.response.DispatchPlanResponse;
import com.freightfox.dispatch.dto.response.VehiclePlanResponse;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.exception.NoVehiclesAvailableException;
import com.freightfox.dispatch.exception.UnknownStrategyException;
import com.freightfox.dispatch.mapper.DispatchPlanMapper;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.VehicleService;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;
import com.freightfox.dispatch.strategy.registry.DispatchStrategyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDispatchServiceTest {

    @Mock
    private OrderService orderService;
    @Mock
    private VehicleService vehicleService;
    @Mock
    private DispatchStrategy greedy;

    private DefaultDispatchService service;

    private final Vehicle veh1 = new Vehicle("VEH001", 100, 12.9716, 77.6413, "Indiranagar");
    private final Vehicle veh2 = new Vehicle("VEH002", 150, 13.0674, 80.2376, "T Nagar");
    private final DeliveryOrder ord1 = new DeliveryOrder("ORD001", 12.9716, 77.5946, "MG Road", 10, Priority.HIGH);
    private final DeliveryOrder ord2 = new DeliveryOrder("ORD002", 13.0827, 80.2707, "Anna Salai", 200, Priority.LOW);

    @BeforeEach
    void setUp() {
        when(greedy.name()).thenReturn(StrategyNames.GREEDY);
        service = new DefaultDispatchService(new DispatchStrategyRegistry(List.of(greedy)), orderService,
                vehicleService, new DispatchPlanMapper());
    }

    @Test
    void unknownStrategyFailsBeforeAnyDatabaseAccess() {
        assertThatThrownBy(() -> service.generatePlan("fastest"))
                .isInstanceOf(UnknownStrategyException.class)
                .hasMessage("Unknown strategy 'fastest'. Allowed values: greedy");
        verifyNoInteractions(orderService, vehicleService);
    }

    @Test
    void noVehiclesIsABadRequestWithTheExactMessage() {
        when(vehicleService.findAllVehicles()).thenReturn(List.of());

        assertThatThrownBy(() -> service.generatePlan(null))
                .isInstanceOf(NoVehiclesAvailableException.class)
                .hasMessage("No vehicles available for dispatch");
        verifyNoInteractions(orderService);
        verify(greedy, never()).plan(any(), any());
    }

    @Test
    void noOrdersGivesEveryVehicleAnEmptyRoute() {
        when(vehicleService.findAllVehicles()).thenReturn(List.of(veh1, veh2));
        when(orderService.findAllOrders()).thenReturn(List.of());
        when(greedy.plan(List.of(), List.of(veh1, veh2))).thenReturn(new DispatchResult(StrategyNames.GREEDY,
                List.of(VehicleRoute.empty(veh1), VehicleRoute.empty(veh2)), List.of()));

        DispatchPlanResponse plan = service.generatePlan("");

        assertThat(plan.dispatchPlan()).containsExactly(
                new VehiclePlanResponse("VEH001", 0, "0.00 km", List.of()),
                new VehiclePlanResponse("VEH002", 0, "0.00 km", List.of()));
        assertThat(plan.unassignedOrders()).isEmpty();
        assertThat(plan.strategy()).isEqualTo("greedy");
    }

    @Test
    void delegatesToTheResolvedStrategyAndMapsItsResult() {
        when(vehicleService.findAllVehicles()).thenReturn(List.of(veh1, veh2));
        when(orderService.findAllOrders()).thenReturn(List.of(ord1, ord2));
        when(greedy.plan(List.of(ord1, ord2), List.of(veh1, veh2))).thenReturn(new DispatchResult(
                StrategyNames.GREEDY,
                List.of(new VehicleRoute(veh1, List.of(ord1), 10, 5.0612), VehicleRoute.empty(veh2)),
                List.of(new UnassignedOrder(ord2, UnassignmentReasons.EXCEEDS_EVERY_VEHICLE))));

        DispatchPlanResponse plan = service.generatePlan("GREEDY");

        assertThat(plan.strategy()).isEqualTo("greedy");
        assertThat(plan.dispatchPlan()).hasSize(2);
        assertThat(plan.dispatchPlan().get(0).totalDistance()).isEqualTo("5.06 km");
        assertThat(plan.dispatchPlan().get(0).assignedOrders()).extracting("orderId").containsExactly("ORD001");
        assertThat(plan.unassignedOrders()).singleElement()
                .satisfies(u -> {
                    assertThat(u.orderId()).isEqualTo("ORD002");
                    assertThat(u.reason()).isEqualTo(UnassignmentReasons.EXCEEDS_EVERY_VEHICLE);
                });
    }

    @Test
    void reportsTheStrategyThatActuallyRan() {
        DispatchStrategy ortools = org.mockito.Mockito.mock(DispatchStrategy.class);
        when(ortools.name()).thenReturn(StrategyNames.ORTOOLS);
        service = new DefaultDispatchService(new DispatchStrategyRegistry(List.of(greedy, ortools)), orderService,
                vehicleService, new DispatchPlanMapper());
        when(vehicleService.findAllVehicles()).thenReturn(List.of(veh1));
        when(orderService.findAllOrders()).thenReturn(List.of());
        // OR-Tools fell back to greedy (e.g. too many orders): the response says what really ran.
        when(ortools.plan(any(), any())).thenReturn(
                new DispatchResult(StrategyNames.GREEDY, List.of(VehicleRoute.empty(veh1)), List.of()));

        assertThat(service.generatePlan("ortools").strategy()).isEqualTo("greedy");
        verify(greedy, never()).plan(any(), any());
    }
}
