package com.freightfox.dispatch.controller;

import com.freightfox.dispatch.service.VehicleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class ServerTimingIntegrationTest {

    private static final String TIMING = "app;dur=\\d+\\.\\d";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VehicleService vehicleService;

    @AfterEach
    void cleanUp() {
        vehicleService.deleteAllVehicles();
    }

    @Test
    void successResponsesCarryServerTiming() throws Exception {
        MvcResult result = mockMvc.perform(post(ApiMessages.BASE_PATH + ApiMessages.VEHICLES_PATH)
                .contentType(MediaType.APPLICATION_JSON).content(OpenApiExamples.VEHICLES_REQUEST)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader(ServerTimingAdvice.HEADER)).matches(TIMING);
    }

    @Test
    void errorResponsesCarryServerTimingToo() throws Exception {
        MvcResult result = mockMvc.perform(get(ApiMessages.BASE_PATH + ApiMessages.PLAN_PATH)
                .param(ApiMessages.STRATEGY_PARAM, "fastest")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getHeader(ServerTimingAdvice.HEADER)).matches(TIMING);
    }

    @Test
    void rootRedirectsToSwaggerUi() throws Exception {
        MvcResult result = mockMvc.perform(get("/")).andReturn();

        assertThat(result.getResponse().getStatus()).isBetween(300, 399);
        assertThat(result.getResponse().getRedirectedUrl()).contains("swagger-ui");
    }

    @Test
    void noHeaderWithoutRecordedStart() {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ServletServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

        Object body = new ServerTimingAdvice().beforeBodyWrite("body", mock(MethodParameter.class),
                MediaType.APPLICATION_JSON, null, new ServletServerHttpRequest(new MockHttpServletRequest()), response);

        assertThat(body).isEqualTo("body");
        assertThat(response.getHeaders().containsKey(ServerTimingAdvice.HEADER)).isFalse();
    }

    @Test
    void noHeaderForNonServletRequests() {
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());

        new ServerTimingAdvice().beforeBodyWrite("body", mock(MethodParameter.class), MediaType.APPLICATION_JSON,
                null, mock(ServerHttpRequest.class), response);

        assertThat(response.getHeaders().containsKey(ServerTimingAdvice.HEADER)).isFalse();
    }
}
