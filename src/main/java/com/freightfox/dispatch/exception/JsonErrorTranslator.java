package com.freightfox.dispatch.exception;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.freightfox.dispatch.dto.response.ErrorResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns an unreadable request body ({@link HttpMessageNotReadableException}) into a precise,
 * client-friendly {@link ErrorResponse}: which field (JSON path such as {@code orders[0].priority}),
 * which value, and what was expected. Never exposes Jackson internals.
 */
final class JsonErrorTranslator {

    /** Name used for the path when the problem is the body itself (e.g. an array instead of an object). */
    static final String ROOT = "body";

    private JsonErrorTranslator() {
    }

    static ErrorResponse translate(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause == null) {
            // Spring raises this without a cause for an empty or absent body.
            return ErrorResponse.of(ErrorMessages.BODY_MISSING, List.of(ErrorMessages.BODY_MISSING_DETAIL));
        }
        if (cause instanceof InvalidFormatException invalidFormat) {
            return invalidValue(invalidFormat);
        }
        if (cause instanceof MismatchedInputException mismatched) {
            String path = path(mismatched);
            String type = TypeNames.of(mismatched.getTargetType());
            return ErrorResponse.of(String.format(ErrorMessages.INVALID_FIELD_TYPE, path, type),
                    List.of(String.format(ErrorMessages.EXPECTED_TYPE_DETAIL, path, type)));
        }
        // Syntax errors inside nested objects arrive wrapped in a JsonMappingException.
        JsonParseException parse = findCause(cause, JsonParseException.class);
        return parse != null ? malformed(parse.getLocation()) : ErrorResponse.of(ErrorMessages.MALFORMED_JSON);
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    private static ErrorResponse invalidValue(InvalidFormatException ex) {
        String path = path(ex);
        String value = ErrorMessages.echo(ex.getValue());
        Class<?> targetType = ex.getTargetType();
        if (targetType != null && targetType.isEnum()) {
            String allowed = enumValues(targetType);
            return ErrorResponse.of(String.format(ErrorMessages.INVALID_ENUM_VALUE, value, path, allowed),
                    List.of(String.format(ErrorMessages.INVALID_ENUM_VALUE_DETAIL, path, allowed)));
        }
        String type = TypeNames.of(targetType);
        return ErrorResponse.of(String.format(ErrorMessages.INVALID_FIELD_VALUE, value, path, type),
                List.of(String.format(ErrorMessages.EXPECTED_TYPE_DETAIL, path, type)));
    }

    private static ErrorResponse malformed(JsonLocation location) {
        if (location == null || location.getLineNr() < 0) {
            return ErrorResponse.of(ErrorMessages.MALFORMED_JSON);
        }
        return ErrorResponse.of(ErrorMessages.MALFORMED_JSON, List.of(
                String.format(ErrorMessages.MALFORMED_JSON_DETAIL, location.getLineNr(), location.getColumnNr())));
    }

    /** Builds e.g. {@code orders[0].priority} from Jackson's reference chain. */
    static String path(JsonMappingException ex) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : ex.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? ROOT : path.toString();
    }

    /** "HIGH, MEDIUM, LOW" for Priority; generic for any enum. */
    static String enumValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(constant -> ((Enum<?>) constant).name())
                .collect(Collectors.joining(", "));
    }
}
