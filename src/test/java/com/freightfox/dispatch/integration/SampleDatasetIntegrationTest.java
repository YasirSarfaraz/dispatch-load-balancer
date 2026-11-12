package com.freightfox.dispatch.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freightfox.dispatch.dto.response.DispatchPlanResponse;
import com.freightfox.dispatch.dto.response.OrderResponse;
import com.freightfox.dispatch.dto.response.UnassignedOrderResponse;
import com.freightfox.dispatch.dto.response.VehiclePlanResponse;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.strategy.StrategyNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test over the real HTTP layer, services, H2 database and both strategies, using the
 * section 9 sample dataset: 30 orders (645 weight) against 5 vehicles (500 capacity).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SampleDatasetIntegrationTest {

    private static final int SAMPLE_ORDER_COUNT = 30;
    private static final int SAMPLE_HIGH_COUNT = 12;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void loadSampleData() throws Exception {
        mockMvc.perform(delete("/api/dispatch/orders")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/dispatch/vehicles")).andExpect(status().isOk());

        mockMvc.perform(post("/api/dispatch/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resource("data/sample-orders.json")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Delivery orders accepted."))
                .andExpect(jsonPath("$.status").value("success"));

        mockMvc.perform(post("/api/dispatch/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resource("data/sample-vehicles.json")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Vehicle details accepted."))
                .andExpect(jsonPath("$.status").value("success"));
    }

    @ParameterizedTest(name = "strategy={0}")
    @ValueSource(strings = {StrategyNames.GREEDY, StrategyNames.ORTOOLS})
    void sampleDatasetSatisfiesEveryConstraint(String strategy) throws Exception {
        DispatchPlanResponse plan = fetchPlan(strategy);
        Map<String, Integer> capacities = vehicleCapacities();

        assertThat(plan.strategy()).isEqualTo(strategy);
        assertThat(plan.dispatchPlan()).extracting(VehiclePlanResponse::vehicleId)
                .containsExactly("VEH001", "VEH002", "VEH003", "VEH004", "VEH005");

        List<OrderResponse> assigned = plan.dispatchPlan().stream()
                .flatMap(v -> v.assignedOrders().stream())
                .toList();

        // All 12 HIGH orders are assigned.
        assertThat(assigned).filteredOn(o -> o.priority() == Priority.HIGH).hasSize(SAMPLE_HIGH_COUNT);

        // No vehicle over capacity, and totalLoad is the sum of its orders.
        for (VehiclePlanResponse vehicle : plan.dispatchPlan()) {
            int load = vehicle.assignedOrders().stream().mapToInt(OrderResponse::packageWeight).sum();
            assertThat(vehicle.totalLoad()).isEqualTo(load);
            assertThat(load).isLessThanOrEqualTo(capacities.get(vehicle.vehicleId()));
            assertThat(vehicle.totalDistance()).matches("\\d+\\.\\d{2} km");
        }

        // assigned + unassigned = 30, each order exactly once.
        Set<String> seen = new HashSet<>();
        Stream.concat(assigned.stream().map(OrderResponse::orderId),
                        plan.unassignedOrders().stream().map(UnassignedOrderResponse::orderId))
                .forEach(id -> assertThat(seen.add(id)).as("order %s appears once", id).isTrue());
        assertThat(seen).hasSize(SAMPLE_ORDER_COUNT);

        // Fleet capacity is 500 and orders weigh 645, so something must be unassigned - all of it LOW.
        assertThat(plan.unassignedOrders()).isNotEmpty();
        assertThat(plan.unassignedOrders()).allSatisfy(o -> {
            assertThat(o.priority()).isEqualTo(Priority.LOW);
            assertThat(o.reason()).isNotBlank();
        });
    }

    @Test
    void orToolsIsAtLeastAsShortAsGreedyOnSampleData() throws Exception {
        double greedy = totalKm(fetchPlan(StrategyNames.GREEDY));
        double orTools = totalKm(fetchPlan(StrategyNames.ORTOOLS));

        // Per-vehicle distances are rounded to 2 decimals in the response; allow that rounding.
        assertThat(orTools).isLessThanOrEqualTo(greedy + 0.05);
    }

    @Test
    void defaultStrategyIsGreedyAndUpsertUpdatesExistingOrder() throws Exception {
        String updated = """
                {"orders": [{"orderId": "ORD001", "latitude": 28.6139, "longitude": 77.2090,
                  "address": "Connaught Place, Delhi, India", "packageWeight": 16, "priority": "HIGH"}]}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(updated))
                .andExpect(status().isOk());

        DispatchPlanResponse plan = readPlan(mockMvc.perform(get("/api/dispatch/plan"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(plan.strategy()).isEqualTo(StrategyNames.GREEDY);
        List<OrderResponse> all = plan.dispatchPlan().stream().flatMap(v -> v.assignedOrders().stream()).toList();
        assertThat(all).filteredOn(o -> o.orderId().equals("ORD001"))
                .singleElement().extracting(OrderResponse::packageWeight).isEqualTo(16);
        int total = all.size() + plan.unassignedOrders().size();
        assertThat(total).isEqualTo(SAMPLE_ORDER_COUNT);
    }

    @Test
    void noVehiclesGives400AndNoOrdersGivesEmptyPlan() throws Exception {
        mockMvc.perform(delete("/api/dispatch/orders")).andExpect(status().isOk());

        DispatchPlanResponse empty = fetchPlan(StrategyNames.GREEDY);
        assertThat(empty.dispatchPlan()).hasSize(5).allSatisfy(v -> {
            assertThat(v.totalLoad()).isZero();
            assertThat(v.totalDistance()).isEqualTo("0.00 km");
            assertThat(v.assignedOrders()).isEmpty();
        });
        assertThat(empty.unassignedOrders()).isEmpty();

        mockMvc.perform(delete("/api/dispatch/vehicles")).andExpect(status().isOk());
        mockMvc.perform(get("/api/dispatch/plan"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("No vehicles available for dispatch"));
    }

    private DispatchPlanResponse fetchPlan(String strategy) throws Exception {
        String body = mockMvc.perform(get("/api/dispatch/plan").param("strategy", strategy))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return readPlan(body);
    }

    private DispatchPlanResponse readPlan(String body) throws IOException {
        return objectMapper.readValue(body, DispatchPlanResponse.class);
    }

    private Map<String, Integer> vehicleCapacities() throws IOException {
        var root = objectMapper.readTree(resource("data/sample-vehicles.json"));
        return Stream.of(objectMapper.treeToValue(root.get("vehicles"), Map[].class))
                .collect(Collectors.toMap(v -> (String) v.get("vehicleId"), v -> (Integer) v.get("capacity")));
    }

    private static double totalKm(DispatchPlanResponse plan) {
        return plan.dispatchPlan().stream()
                .mapToDouble(v -> Double.parseDouble(v.totalDistance().replace(" km", "").trim()))
                .sum();
    }

    private static String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
