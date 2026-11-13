package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.service.DispatchService;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.VehicleService;
import com.freightfox.dispatch.strategy.greedy.GreedyPriorityDispatchStrategy;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * A plan must be computed from one consistent view of the data: the vehicles and the orders are read in
 * the <b>same</b> read-only REPEATABLE READ transaction, and the algorithm runs outside any transaction.
 * (That a commit between the two reads stays invisible is proven by {@code PersistenceIntegrationTest}.)
 */
@SpringBootTest
class DispatchSnapshotConsistencyIntegrationTest {

    @MockitoSpyBean
    private VehicleService vehicleService;

    @MockitoSpyBean
    private OrderService orderService;

    @MockitoSpyBean
    private GreedyPriorityDispatchStrategy greedy;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final List<TransactionView> seen = new ArrayList<>();

    @BeforeEach
    void loadData() {
        orderService.deleteAllOrders();
        vehicleService.deleteAllVehicles();
        vehicleService.upsertVehicles(List.of(new Vehicle("VEH-S1", 100, 28.6139, 77.209, "Connaught Place")));
        orderService.upsertOrders(List.of(
                new DeliveryOrder("ORD-S1", 28.62, 77.21, "Connaught Place, Delhi, India", 5, Priority.HIGH)));
    }

    @AfterEach
    void cleanUp() {
        orderService.deleteAllOrders();
        vehicleService.deleteAllVehicles();
    }

    @Test
    void vehiclesAndOrdersAreReadInOneReadOnlyRepeatableReadTransactionAndPlannedOutsideIt() {
        doAnswer(invocation -> record("vehicles", invocation.callRealMethod()))
                .when(vehicleService).findAllVehicles();
        doAnswer(invocation -> record("orders", invocation.callRealMethod()))
                .when(orderService).findAllOrders();
        doAnswer(invocation -> record("plan", invocation.callRealMethod()))
                .when(greedy).plan(any(), any());

        dispatchService.generatePlan("greedy");

        assertThat(seen).extracting(TransactionView::step).containsExactly("vehicles", "orders", "plan");
        TransactionView vehicles = seen.get(0);
        TransactionView orders = seen.get(1);
        TransactionView plan = seen.get(2);

        assertThat(vehicles.active()).isTrue();
        assertThat(vehicles.readOnly()).isTrue();
        assertThat(vehicles.isolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(orders.transaction()).as("orders are read in the same transaction as vehicles")
                .isSameAs(vehicles.transaction());
        assertThat(plan.active()).as("planning holds no transaction or connection").isFalse();
    }

    private Object record(String step, Object result) {
        seen.add(new TransactionView(step,
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                TransactionSynchronizationManager.getResource(entityManagerFactory)));
        return result;
    }

    private record TransactionView(String step, boolean active, boolean readOnly, Integer isolation,
                                   Object transaction) {
    }
}
