package com.freightfox.dispatch.controller;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.VehicleService;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;
import com.freightfox.dispatch.strategy.registry.DispatchStrategyRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static com.freightfox.dispatch.controller.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full stack (controller, validation, services, registry, mapper, JPA, H2) with a trivial test-only
 * strategy, so the pipeline is verified independently of the real algorithms.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:dispatch-api-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
@AutoConfigureMockMvc
@Import(DispatchApiIntegrationTest.TestStrategyConfig.class)
class DispatchApiIntegrationTest {

    static final String TEST_STRATEGY = "test-first-vehicle";

    /** Puts every order on the first vehicle in input order (ignores capacity; for wiring tests only). */
    static final class FirstVehicleStrategy implements DispatchStrategy {
        @Override
        public String name() {
            return TEST_STRATEGY;
        }

        @Override
        public DispatchResult plan(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
            List<DeliveryOrder> heavy = orders.stream().filter(o -> o.getPackageWeight() > 1000).toList();
            List<DeliveryOrder> placed = orders.stream().filter(o -> o.getPackageWeight() <= 1000).toList();
            int load = placed.stream().mapToInt(DeliveryOrder::getPackageWeight).sum();
            List<VehicleRoute> routes = new ArrayList<>();
            routes.add(placed.isEmpty() ? VehicleRoute.empty(vehicles.get(0))
                    : new VehicleRoute(vehicles.get(0), placed, load, 12.3456));
            vehicles.stream().skip(1).map(VehicleRoute::empty).forEach(routes::add);
            return new DispatchResult(TEST_STRATEGY, routes, heavy.stream()
                    .map(o -> new UnassignedOrder(o, UnassignmentReasons.EXCEEDS_EVERY_VEHICLE)).toList());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStrategyConfig {
        @Bean
        DispatchStrategy firstVehicleStrategy() {
            return new FirstVehicleStrategy();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderService orderService;
    @Autowired
    private VehicleService vehicleService;
    @Autowired
    private DispatchStrategyRegistry registry;

    @AfterEach
    void cleanUp() {
        orderService.deleteAllOrders();
        vehicleService.deleteAllVehicles();
    }

    private void postJson(String path, String body) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void planWithoutVehiclesIsABadRequest() throws Exception {
        postJson("/api/dispatch/orders", OpenApiExamples.ORDERS_REQUEST);

        assertError(mockMvc.perform(get("/api/dispatch/plan").param("strategy", TEST_STRATEGY)).andReturn(),
                400, "No vehicles available for dispatch");
    }

    @Test
    void unknownStrategyIsABadRequestEvenWithAnEmptyDatabase() throws Exception {
        List<String> errors = assertError(
                mockMvc.perform(get("/api/dispatch/plan").param("strategy", "fastest")).andReturn(),
                400, "Unknown strategy 'fastest'. Allowed values: " + String.join(", ", registry.availableNames()));
        assertThat(errors).hasSize(1);
    }

    @Test
    void planWithVehiclesButNoOrdersListsEveryVehicleEmpty() throws Exception {
        postJson("/api/dispatch/vehicles", OpenApiExamples.VEHICLES_REQUEST);

        mockMvc.perform(get("/api/dispatch/plan").param("strategy", TEST_STRATEGY.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"dispatchPlan": [
                          {"vehicleId": "VEH001", "totalLoad": 0, "totalDistance": "0.00 km", "assignedOrders": []},
                          {"vehicleId": "VEH002", "totalLoad": 0, "totalDistance": "0.00 km", "assignedOrders": []}],
                         "unassignedOrders": [], "strategy": "test-first-vehicle"}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void fullFlowStoresUpsertsAndPlansSortedById() throws Exception {
        postJson("/api/dispatch/vehicles", OpenApiExamples.VEHICLES_REQUEST);
        postJson("/api/dispatch/orders", OpenApiExamples.ORDERS_REQUEST);
        // Upsert: ORD002 changes, ORD000 is new and sorts first.
        postJson("/api/dispatch/orders", """
                {"orders": [
                  {"orderId": "ORD002", "latitude": 13.0, "longitude": 80.0, "address": "Updated address",
                   "packageWeight": 5000, "priority": "LOW"},
                  {"orderId": "ORD000", "latitude": 12.0, "longitude": 77.0, "address": "New order",
                   "packageWeight": 1, "priority": "MEDIUM"}
                ]}
                """);

        assertThat(orderService.countOrders()).isEqualTo(3);
        mockMvc.perform(get("/api/dispatch/plan").param("strategy", TEST_STRATEGY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value(TEST_STRATEGY))
                .andExpect(jsonPath("$.dispatchPlan[0].vehicleId").value("VEH001"))
                .andExpect(jsonPath("$.dispatchPlan[0].totalLoad").value(11))
                .andExpect(jsonPath("$.dispatchPlan[0].totalDistance").value("12.35 km"))
                .andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[0].orderId").value("ORD000"))
                .andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[1].orderId").value("ORD001"))
                .andExpect(jsonPath("$.dispatchPlan[1].vehicleId").value("VEH002"))
                .andExpect(jsonPath("$.unassignedOrders[0].orderId").value("ORD002"))
                .andExpect(jsonPath("$.unassignedOrders[0].address").value("Updated address"))
                .andExpect(jsonPath("$.unassignedOrders[0].priority").value("LOW"))
                .andExpect(jsonPath("$.unassignedOrders[0].reason")
                        .value("Package weight exceeds capacity of every vehicle"));
    }

    @Test
    void duplicateIdsAreRejectedAndNothingIsStored() throws Exception {
        String body = """
                {"orders": [
                  {"orderId": "ORD001", "latitude": 12.9, "longitude": 77.5, "address": "A", "packageWeight": 1,
                   "priority": "HIGH"},
                  {"orderId": "ORD001", "latitude": 12.9, "longitude": 77.5, "address": "B", "packageWeight": 2,
                   "priority": "LOW"}
                ]}
                """;

        List<String> errors = assertError(mockMvc.perform(post("/api/dispatch/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(),
                400, "Duplicate orderId values in request");

        assertThat(errors).containsExactly("orders: duplicate orderId 'ORD001'");
        assertThat(orderService.countOrders()).isZero();
    }

    @Test
    void deleteEndpointsClearTheData() throws Exception {
        postJson("/api/dispatch/vehicles", OpenApiExamples.VEHICLES_SAMPLE_DATASET);
        postJson("/api/dispatch/orders", OpenApiExamples.ORDERS_SAMPLE_DATASET);
        assertThat(orderService.countOrders()).isEqualTo(30);
        assertThat(vehicleService.countVehicles()).isEqualTo(5);

        mockMvc.perform(delete("/api/dispatch/orders")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/dispatch/vehicles")).andExpect(status().isOk());

        assertThat(orderService.countOrders()).isZero();
        assertThat(vehicleService.countVehicles()).isZero();
    }

    @Test
    void openApiDocumentDescribesTheApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Dispatch Load Balancer API"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"))
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.paths['/api/dispatch/orders'].post.requestBody.content['application/json']"
                        + ".examples['Assignment example'].value.orders[0].orderId").value("ORD001"))
                .andExpect(jsonPath("$.paths['/api/dispatch/orders'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/dispatch/plan'].get.parameters[0].schema.enum[1]").value("ortools"))
                .andExpect(jsonPath("$.paths['/api/dispatch/vehicles'].delete").exists());
    }
}
