package com.freightfox.dispatch.controller;

import com.freightfox.dispatch.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A 50,000-order JSON body (about 7 MB) is accepted, validated and upserted end to end. */
@SpringBootTest
@AutoConfigureMockMvc
class LargeBatchIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LargeBatchIntegrationTest.class);
    private static final int ORDER_COUNT = 50_000;
    private static final String[] PRIORITIES = {"HIGH", "MEDIUM", "LOW"};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderService orderService;

    @AfterEach
    void cleanUp() {
        orderService.deleteAllOrders();
    }

    static String ordersJson(int count, int weightOffset) {
        StringBuilder json = new StringBuilder(count * 160).append("{\"orders\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(String.format(Locale.ROOT,
                    "{\"orderId\":\"ORD%06d\",\"latitude\":%.6f,\"longitude\":%.6f,"
                            + "\"address\":\"Near City %d, India\",\"packageWeight\":%d,\"priority\":\"%s\"}",
                    i, 8.0 + (i % 2800) * 0.01, 68.0 + (i % 2900) * 0.01, i % 30, 1 + (i + weightOffset) % 50,
                    PRIORITIES[i % 3]));
        }
        return json.append("]}").toString();
    }

    @Test
    void acceptsAndUpserts50000OrdersInOneRequest() throws Exception {
        String body = ordersJson(ORDER_COUNT, 0);
        log.info("Posting {} orders, {} bytes", ORDER_COUNT, body.length());

        long start = System.nanoTime();
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\": \"Delivery orders accepted.\", \"status\": \"success\"}",
                        JsonCompareMode.STRICT));
        long insertMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(orderService.countOrders()).isEqualTo(ORDER_COUNT);

        // Same IDs again with different weights: all 50,000 become updates, no new rows.
        start = System.nanoTime();
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(ordersJson(ORDER_COUNT, 7)))
                .andExpect(status().isOk());
        long updateMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(orderService.countOrders()).isEqualTo(ORDER_COUNT);
        assertThat(orderService.findAllOrders().get(0).getPackageWeight()).isEqualTo(8);
        log.info("50,000-order request: insert {} ms, update {} ms (MockMvc, H2 in memory)", insertMs, updateMs);
    }
}
