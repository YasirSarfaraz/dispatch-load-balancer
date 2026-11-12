package com.freightfox.dispatch.testdata;

import com.freightfox.dispatch.config.TestDataProperties;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.exception.BadRequestException;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.UpsertResult;
import com.freightfox.dispatch.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestDataServiceTest {

    private static final TestDataProperties PROPERTIES = new TestDataProperties(true, 50_000, 2_000);

    @Mock
    private OrderService orderService;
    @Mock
    private VehicleService vehicleService;

    private TestDataGenerator generator;
    private TestDataService service;

    @BeforeEach
    void setUp() {
        generator = spy(new TestDataGenerator());
        service = new TestDataService(generator, orderService, vehicleService, PROPERTIES);
    }

    @Test
    void clearsExistingDataThenUpsertsGeneratedDataThroughTheServices() {
        when(vehicleService.upsertVehicles(anyList())).thenReturn(new UpsertResult(10, 0));
        when(orderService.upsertOrders(anyList())).thenReturn(new UpsertResult(200, 0));

        TestDataResult result = service.replaceWithGeneratedData(200, 10, 42L);

        assertThat(result.ordersGenerated()).isEqualTo(200);
        assertThat(result.vehiclesGenerated()).isEqualTo(10);
        assertThat(result.seed()).isEqualTo(42L);
        assertThat(result.elapsedMs()).isNotNegative();

        InOrder order = inOrder(generator, orderService, vehicleService);
        order.verify(generator).generate(200, 10, 42L);
        order.verify(orderService).deleteAllOrders();
        order.verify(vehicleService).deleteAllVehicles();
        order.verify(vehicleService).upsertVehicles(anyList());
        order.verify(orderService).upsertOrders(anyList());
        order.verifyNoMoreInteractions();
    }

    @Test
    void storesExactlyTheGeneratedEntities() {
        when(vehicleService.upsertVehicles(anyList())).thenReturn(new UpsertResult(5, 0));
        when(orderService.upsertOrders(anyList())).thenReturn(new UpsertResult(50, 0));

        service.replaceWithGeneratedData(50, 5, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeliveryOrder>> orders = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Vehicle>> vehicles = ArgumentCaptor.forClass(List.class);
        verify(orderService).upsertOrders(orders.capture());
        verify(vehicleService).upsertVehicles(vehicles.capture());

        GeneratedDataset expected = new TestDataGenerator().generate(50, 5, 7L);
        assertThat(orders.getValue()).containsExactlyElementsOf(expected.orders());
        assertThat(vehicles.getValue()).containsExactlyElementsOf(expected.vehicles());
    }

    @Test
    void acceptsTheConfiguredMaximums() {
        when(vehicleService.upsertVehicles(anyList())).thenReturn(new UpsertResult(2_000, 0));
        when(orderService.upsertOrders(anyList())).thenReturn(new UpsertResult(50_000, 0));

        TestDataResult result = service.replaceWithGeneratedData(50_000, 2_000, 1L);

        assertThat(result.ordersGenerated()).isEqualTo(50_000);
        assertThat(result.vehiclesGenerated()).isEqualTo(2_000);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 50_001})
    void rejectsOrderCountOutOfRange(int orders) {
        BadRequestException ex = catchThrowableOfType(BadRequestException.class,
                () -> service.replaceWithGeneratedData(orders, 100, 42L));

        assertThat(ex.getMessage()).isEqualTo("orders must be between 1 and 50000");
        assertThat(ex.getErrors()).containsExactly("orders: must be between 1 and 50000");
        verifyNoInteractions(orderService, vehicleService);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 2_001})
    void rejectsVehicleCountOutOfRange(int vehicles) {
        BadRequestException ex = catchThrowableOfType(BadRequestException.class,
                () -> service.replaceWithGeneratedData(5_000, vehicles, 42L));

        assertThat(ex.getMessage()).isEqualTo("vehicles must be between 1 and 2000");
        assertThat(ex.getErrors()).containsExactly("vehicles: must be between 1 and 2000");
        verifyNoInteractions(orderService, vehicleService);
    }

    @Test
    void reportsBothViolationsTogether() {
        BadRequestException ex = catchThrowableOfType(BadRequestException.class,
                () -> service.replaceWithGeneratedData(60_000, 0, 42L));

        assertThat(ex.getMessage())
                .isEqualTo("orders must be between 1 and 50000; vehicles must be between 1 and 2000");
        assertThat(ex.getErrors()).containsExactly("orders: must be between 1 and 50000",
                "vehicles: must be between 1 and 2000");
    }

    @Test
    void usesTheConfiguredLimits() {
        TestDataService small = new TestDataService(generator, orderService, vehicleService,
                new TestDataProperties(true, 10, 3));

        BadRequestException ex = catchThrowableOfType(BadRequestException.class,
                () -> small.replaceWithGeneratedData(11, 3, 1L));

        assertThat(ex.getErrors()).containsExactly("orders: must be between 1 and 10");
        verifyNoInteractions(orderService, vehicleService);
    }
}
