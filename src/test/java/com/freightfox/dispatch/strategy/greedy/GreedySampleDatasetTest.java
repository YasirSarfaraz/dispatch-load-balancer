package com.freightfox.dispatch.strategy.greedy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.freightfox.dispatch.strategy.greedy.GreedyTestData.assertContract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The section 9 sample dataset: 30 orders (645 kg) for 5 vehicles (500 kg). */
class GreedySampleDatasetTest {

    private static List<DeliveryOrder> orders;
    private static List<Vehicle> vehicles;
    private static DispatchResult result;

    @BeforeAll
    static void planSampleData() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        orders = new ArrayList<>();
        for (JsonNode node : read(mapper, "/data/sample-orders.json").get("orders")) {
            orders.add(new DeliveryOrder(node.get("orderId").asText(), node.get("latitude").asDouble(),
                    node.get("longitude").asDouble(), node.get("address").asText(),
                    node.get("packageWeight").asInt(), Priority.valueOf(node.get("priority").asText())));
        }
        vehicles = new ArrayList<>();
        for (JsonNode node : read(mapper, "/data/sample-vehicles.json").get("vehicles")) {
            vehicles.add(new Vehicle(node.get("vehicleId").asText(), node.get("capacity").asInt(),
                    node.get("currentLatitude").asDouble(), node.get("currentLongitude").asDouble(),
                    node.get("currentAddress").asText()));
        }
        result = GreedyTestData.strategy().plan(orders, vehicles);
    }

    private static JsonNode read(ObjectMapper mapper, String resource) throws IOException {
        try (InputStream in = GreedySampleDatasetTest.class.getResourceAsStream(resource)) {
            return mapper.readTree(in);
        }
    }

    @Test
    void datasetIsAsSpecified() {
        assertThat(orders).hasSize(30);
        assertThat(vehicles).hasSize(5);
        assertThat(orders.stream().mapToInt(DeliveryOrder::getPackageWeight).sum()).isEqualTo(645);
        assertThat(vehicles.stream().mapToInt(Vehicle::getCapacity).sum()).isEqualTo(500);
    }

    @Test
    void allTwelveHighOrdersAreAssigned() {
        long assignedHigh = result.routes().stream().flatMap(r -> r.stops().stream())
                .filter(o -> o.getPriority() == Priority.HIGH).count();
        assertThat(assignedHigh).isEqualTo(12);
    }

    @Test
    void noVehicleIsOverCapacityAndEveryOrderIsAccountedFor() {
        assertContract(result, orders, vehicles);
        assertThat(result.assignedCount() + result.unassignedOrders().size()).isEqualTo(30);
    }

    @Test
    void onlyLowOrdersAreUnassignedForLackOfRemainingCapacity() {
        assertThat(result.unassignedOrders()).isNotEmpty();
        assertThat(result.unassignedOrders()).extracting(u -> u.order().getPriority()).containsOnly(Priority.LOW);
        assertThat(result.unassignedOrders()).extracting(UnassignedOrder::reason)
                .containsOnly(UnassignmentReasons.INSUFFICIENT_REMAINING_CAPACITY);
    }

    @Test
    void producesTheExpectedPlan() {
        assertThat(result.routes()).extracting(r -> r.vehicle().getVehicleId())
                .containsExactly("VEH001", "VEH002", "VEH003", "VEH004", "VEH005");
        assertRoute(0, 100, 14.442, "ORD026", "ORD007", "ORD011", "ORD015", "ORD001");
        assertRoute(1, 80, 0.0, "ORD020", "ORD023", "ORD027");
        assertRoute(2, 115, 24.743, "ORD013", "ORD005", "ORD009", "ORD017", "ORD010");
        assertRoute(3, 90, 19.795, "ORD029", "ORD004", "ORD008", "ORD012");
        assertRoute(4, 105, 14.442, "ORD018", "ORD021", "ORD025", "ORD002", "ORD006");
        assertThat(result.totalDistanceKm()).isCloseTo(73.423, within(0.001));
        assertThat(result.unassignedOrders()).extracting(u -> u.order().getOrderId())
                .containsExactly("ORD003", "ORD014", "ORD016", "ORD019", "ORD022", "ORD024", "ORD028", "ORD030");
    }

    private static void assertRoute(int index, int load, double distanceKm, String... stopIds) {
        VehicleRoute route = result.routes().get(index);
        assertThat(route.stops()).extracting(DeliveryOrder::getOrderId).as(route.vehicle().getVehicleId())
                .containsExactly(stopIds);
        assertThat(route.totalLoad()).isEqualTo(load);
        assertThat(route.totalDistanceKm()).isCloseTo(distanceKm, within(0.001));
    }
}
