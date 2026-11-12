package com.freightfox.dispatch.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Asserts the single error shape {"status":"error","message":...,"errors":[...],"timestamp":...}. */
public final class ErrorResponseAssertions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ErrorResponseAssertions() {
    }

    /**
     * Checks status code, the exact four-field shape, the message, a parseable timestamp and that no
     * stack trace or exception class leaks. Returns the errors array for further assertions.
     */
    public static List<String> assertError(MvcResult result, int status, String message) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        String content = result.getResponse().getContentAsString();
        JsonNode body = MAPPER.readTree(content);

        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("status", "message", "errors", "timestamp");
        assertThat(body.get("status").asText()).isEqualTo("error");
        assertThat(body.get("message").asText()).isEqualTo(message);
        assertThat(body.get("errors").isArray()).isTrue();
        assertThat(Instant.parse(body.get("timestamp").asText())).isNotNull();
        assertThat(content).doesNotContain("Exception", "java.", "at com.", "trace", "\tat ");

        List<String> errors = new ArrayList<>();
        body.get("errors").forEach(node -> errors.add(node.asText()));
        return errors;
    }
}
