package com.freightfox.dispatch.exception;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.freightfox.dispatch.dto.response.ErrorResponse;
import com.freightfox.dispatch.entity.Priority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonErrorTranslatorTest {

    private static HttpMessageNotReadableException unreadable(Throwable cause) {
        return new HttpMessageNotReadableException("JSON parse error", cause, new MockHttpInputMessage(new byte[0]));
    }

    @Test
    void parseErrorWithoutLocationIsMalformedWithoutDetails() {
        ErrorResponse response = JsonErrorTranslator.translate(unreadable(new JsonParseException((JsonParser) null, "x")));

        assertThat(response.message()).isEqualTo("Malformed JSON request");
        assertThat(response.errors()).isEmpty();
    }

    @Test
    void otherMappingProblemsAreMalformedJson() {
        ErrorResponse response = JsonErrorTranslator.translate(
                unreadable(JsonMappingException.from((JsonParser) null, "cannot construct instance")));

        assertThat(response.message()).isEqualTo("Malformed JSON request");
        assertThat(response.errors()).isEmpty();
    }

    @Test
    void unrelatedCausesAreMalformedJson() {
        assertThat(JsonErrorTranslator.translate(unreadable(new IllegalStateException("boom"))).message())
                .isEqualTo("Malformed JSON request");
    }

    @Test
    void enumValuesAreListedGenerically() {
        assertThat(JsonErrorTranslator.enumValues(Priority.class)).isEqualTo(Priority.allowedValues());
    }

    @ParameterizedTest
    @CsvSource({
            "java.lang.Integer, integer",
            "long, integer",
            "java.math.BigDecimal, number",
            "double, number",
            "java.lang.Boolean, boolean",
            "boolean, boolean",
            "java.lang.String, string",
            "java.util.List, array",
            "com.freightfox.dispatch.entity.Priority, string",
            "java.util.Map, object"
    })
    void typeNamesAreClientFriendly(String className, String expected) throws Exception {
        Class<?> type = switch (className) {
            case "long" -> long.class;
            case "double" -> double.class;
            case "boolean" -> boolean.class;
            default -> Class.forName(className);
        };

        assertThat(TypeNames.of(type)).isEqualTo(expected);
    }

    @Test
    void nullAndArrayTypes() {
        assertThat(TypeNames.of(null)).isEqualTo("value");
        assertThat(TypeNames.of(int[].class)).isEqualTo("array");
        assertThat(TypeNames.of(char.class)).isEqualTo("string");
        assertThat(List.of(TypeNames.of(Map.class), TypeNames.of(BigDecimal.class))).containsExactly("object", "number");
    }

    @Test
    void echoTruncatesLongValuesAndHandlesNull() {
        assertThat(ErrorMessages.echo(null)).isEqualTo("null");
        assertThat(ErrorMessages.echo("short")).isEqualTo("short");
        assertThat(ErrorMessages.echo("y".repeat(100))).hasSize(67).endsWith("...");
    }
}
