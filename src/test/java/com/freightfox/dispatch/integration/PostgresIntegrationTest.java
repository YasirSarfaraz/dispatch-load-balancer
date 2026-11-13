package com.freightfox.dispatch.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The full application with the real {@code prod} profile (application-prod.yml: PostgreSQL driver,
 * HikariCP settings, Flyway, schema validation) against a real PostgreSQL 16. The container's address and
 * credentials are passed through the same DB_* variables Render injects. Runs wherever Docker is available
 * (always in CI) and is skipped otherwise; every other test uses in-memory H2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void renderStyleEnvironment(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearTables() throws Exception {
        mockMvc.perform(delete("/api/dispatch/orders")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/dispatch/vehicles")).andExpect(status().isOk());
    }

    @Test
    void flywayCreatesTheSchemaOnPostgres() throws Exception {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL 16");
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success", String.class))
                .containsExactly("1");

        JsonNode info = json(get("/actuator/info"));
        assertThat(info.at("/database/product").asText()).isEqualTo("PostgreSQL");
    }

    @Test
    void sampleDatasetGivesTheSamePlanAsOnH2() throws Exception {
        upload("/api/dispatch/orders", resource("data/sample-orders.json"));
        upload("/api/dispatch/vehicles", resource("data/sample-vehicles.json"));

        JsonNode plan = json(get("/api/dispatch/plan"));

        double totalKm = 0;
        int assigned = 0;
        for (JsonNode vehicle : plan.get("dispatchPlan")) {
            totalKm += Double.parseDouble(vehicle.get("totalDistance").asText().replace(" km", ""));
            assigned += vehicle.get("assignedOrders").size();
        }
        assertThat(plan.get("strategy").asText()).isEqualTo("greedy");
        assertThat(assigned).isEqualTo(22);
        assertThat(String.format("%.2f", totalKm)).isEqualTo("73.42");
        assertThat(plan.get("unassignedOrders")).hasSize(8);
    }

    @Test
    void orToolsRunsAgainstPostgresData() throws Exception {
        upload("/api/dispatch/orders", resource("data/sample-orders.json"));
        upload("/api/dispatch/vehicles", resource("data/sample-vehicles.json"));

        JsonNode plan = json(get("/api/dispatch/plan").param("strategy", "ortools"));

        assertThat(plan.get("strategy").asText()).isEqualTo("ortools");
        plan.get("unassignedOrders").forEach(order -> assertThat(order.get("priority").asText()).isEqualTo("LOW"));
    }

    @Test
    void largeBatchUpsertInsertsThenUpdatesWithoutDuplicates() throws Exception {
        int count = 5_000;
        upload("/api/dispatch/orders", orders(count, 10));
        assertThat(rowCount("delivery_orders")).isEqualTo(count);

        upload("/api/dispatch/orders", orders(count, 20));

        assertThat(rowCount("delivery_orders")).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_orders WHERE package_weight = 20", Integer.class))
                .isEqualTo(count);
    }

    @Test
    void databaseConstraintsBackTheValidation() {
        Integer before = rowCount("delivery_orders");

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO delivery_orders VALUES ('X1', 0, 0, 'Somewhere', 5, 'URGENT')"));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO delivery_orders VALUES ('X2', 0, 0, 'Somewhere', 0, 'HIGH')"));

        assertThat(rowCount("delivery_orders")).isEqualTo(before);
    }

    private Integer rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String orders(int count, int weight) throws Exception {
        List<Map<String, Object>> orders = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            orders.add(Map.of("orderId", "PG" + i, "latitude", 28.6, "longitude", 77.2,
                    "address", "Connaught Place, Delhi, India", "packageWeight", weight, "priority", "LOW"));
        }
        return objectMapper.writeValueAsString(Map.of("orders", orders));
    }

    private void upload(String path, String body) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    private JsonNode json(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private static String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
