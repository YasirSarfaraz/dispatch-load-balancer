package com.freightfox.dispatch.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level tests of POST /api/dispatch/test-data wired to the real services, database and handler. */
@SpringBootTest
@AutoConfigureMockMvc
class TestDataIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void clear() throws Exception {
        mockMvc.perform(delete("/api/dispatch/orders")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/dispatch/vehicles")).andExpect(status().isOk());
    }

    @Test
    void generatesDatasetThroughUpsertAndPlansIt() throws Exception {
        mockMvc.perform(post("/api/dispatch/test-data")
                        .param("orders", "5000").param("vehicles", "100").param("seed", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.ordersGenerated").value(5000))
                .andExpect(jsonPath("$.vehiclesGenerated").value(100))
                .andExpect(jsonPath("$.seed").value(42))
                .andExpect(jsonPath("$.elapsedMs").value(notNullValue()));

        mockMvc.perform(get("/api/dispatch/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("greedy"))
                .andExpect(jsonPath("$.dispatchPlan", hasSize(100)))
                .andExpect(jsonPath("$.dispatchPlan[*].totalLoad", everyItem(lessThanOrEqualTo(500))));
    }

    @Test
    void orToolsAboveCapFallsBackToGreedy() throws Exception {
        mockMvc.perform(post("/api/dispatch/test-data").param("orders", "1500").param("vehicles", "20"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/dispatch/plan").param("strategy", "ortools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("greedy"))
                .andExpect(jsonPath("$.dispatchPlan", hasSize(20)));
    }

    @ParameterizedTest(name = "orders={0} vehicles={1}")
    @CsvSource({"0,10", "50001,10", "10,0", "10,2001", "-5,10"})
    void outOfRangeParametersAre400(String orders, String vehicles) throws Exception {
        mockMvc.perform(post("/api/dispatch/test-data").param("orders", orders).param("vehicles", vehicles))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message", containsString("must be between")))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void nonNumericParameterIs400() throws Exception {
        mockMvc.perform(post("/api/dispatch/test-data").param("orders", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[0]", containsString("orders")));
    }
}
