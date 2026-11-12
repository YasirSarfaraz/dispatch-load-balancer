package com.freightfox.dispatch.controller;

import com.freightfox.dispatch.dto.response.TestDataResponse;
import com.freightfox.dispatch.testdata.TestDataResult;
import com.freightfox.dispatch.testdata.TestDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link TestDataController}. Range errors (400) are covered at service level in
 * TestDataServiceTest, because the global exception handler is not part of this slice.
 */
@WebMvcTest(TestDataController.class)
class TestDataControllerTest {

    private static final String URL = "/api/dispatch/test-data";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TestDataService testDataService;

    @Test
    void usesDefaultsWhenNoParametersAreGiven() throws Exception {
        when(testDataService.replaceWithGeneratedData(5000, 100, 42L))
                .thenReturn(new TestDataResult(5000, 100, 42L, 123L));

        mockMvc.perform(post(URL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value(TestDataResponse.GENERATED_MESSAGE))
                .andExpect(jsonPath("$.ordersGenerated").value(5000))
                .andExpect(jsonPath("$.vehiclesGenerated").value(100))
                .andExpect(jsonPath("$.seed").value(42))
                .andExpect(jsonPath("$.elapsedMs").value(123));

        verify(testDataService).replaceWithGeneratedData(5000, 100, 42L);
    }

    @Test
    void passesExplicitParametersToTheService() throws Exception {
        when(testDataService.replaceWithGeneratedData(250, 7, 9_000_000_000L))
                .thenReturn(new TestDataResult(250, 7, 9_000_000_000L, 5L));

        mockMvc.perform(post(URL).param("orders", "250").param("vehicles", "7").param("seed", "9000000000"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"message": "Test data generated. All existing orders and vehicles were replaced.",
                         "status": "success", "ordersGenerated": 250, "vehiclesGenerated": 7,
                         "seed": 9000000000, "elapsedMs": 5}""", true));
    }

    @Test
    void getIsNotAllowed() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isMethodNotAllowed());
    }
}
