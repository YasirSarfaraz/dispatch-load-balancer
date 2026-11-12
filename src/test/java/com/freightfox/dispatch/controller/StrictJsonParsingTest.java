package com.freightfox.dispatch.controller;

import com.freightfox.dispatch.mapper.RequestMapper;
import com.freightfox.dispatch.service.DispatchService;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Body parsing is strict: no silent coercion, no overflow, no trailing garbage. */
@WebMvcTest(DispatchController.class)
@Import(RequestMapper.class)
class StrictJsonParsingTest {

    private static final String ORDER_TEMPLATE = """
            {"orders": [{"orderId": "ORD001", "latitude": 28.6139, "longitude": 77.2090,
              "address": "Connaught Place, Delhi, India", "packageWeight": %s, "priority": "HIGH"}]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private VehicleService vehicleService;
    @MockitoBean
    private DispatchService dispatchService;

    @Test
    void integerOverflowNamesTheFieldAndType() throws Exception {
        postOrders(ORDER_TEMPLATE.formatted("3000000000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message", containsString("orders[0].packageWeight")))
                .andExpect(jsonPath("$.errors[0]").value("orders[0].packageWeight: must be of type integer"));
        verify(orderService, never()).upsertOrders(any());
    }

    @Test
    void numericStringIsNotCoercedToNumber() throws Exception {
        postOrders(ORDER_TEMPLATE.formatted("\"10\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message", containsString("orders[0].packageWeight")));
        verify(orderService, never()).upsertOrders(any());
    }

    @Test
    void trailingTokensAreRejected() throws Exception {
        postOrders(ORDER_TEMPLATE.formatted("10") + " xyz")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
        verify(orderService, never()).upsertOrders(any());
    }

    private ResultActions postOrders(String body) throws Exception {
        return mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
