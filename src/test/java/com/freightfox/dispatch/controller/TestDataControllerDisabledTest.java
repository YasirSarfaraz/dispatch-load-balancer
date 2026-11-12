package com.freightfox.dispatch.controller;

import com.freightfox.dispatch.testdata.TestDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With app.test-data.enabled=false the demo endpoint must not be registered at all. */
@WebMvcTest(controllers = TestDataController.class, properties = "app.test-data.enabled=false")
class TestDataControllerDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private TestDataService testDataService;

    @Test
    void controllerIsNotRegistered() throws Exception {
        assertThat(context.getBeansOfType(TestDataController.class)).isEmpty();

        mockMvc.perform(post("/api/dispatch/test-data"))
                .andExpect(status().isNotFound());
    }
}
