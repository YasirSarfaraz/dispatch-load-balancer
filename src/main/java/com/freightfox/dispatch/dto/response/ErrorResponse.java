package com.freightfox.dispatch.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape used by every error response:
 * {"status": "error", "message": "...", "errors": ["field: reason"], "timestamp": "..."}.
 */
@Schema(description = "Error response")
public record ErrorResponse(
        @Schema(example = "error") String status,
        @Schema(example = "Validation failed") String message,
        @Schema(example = "[\"orders[0].latitude: must be less than or equal to 90.0\"]") List<String> errors,
        @Schema(example = "2026-11-12T10:15:30.123Z") Instant timestamp) {

    public static final String ERROR = "error";

    public ErrorResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ErrorResponse of(String message, List<String> errors) {
        return new ErrorResponse(ERROR, message, errors, Instant.now());
    }

    public static ErrorResponse of(String message) {
        return of(message, List.of());
    }
}
