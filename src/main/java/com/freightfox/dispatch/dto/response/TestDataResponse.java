package com.freightfox.dispatch.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body returned by POST /api/dispatch/test-data. */
@Schema(description = "Result of a test-data generation run")
public record TestDataResponse(
        @Schema(example = "Test data generated. All existing orders and vehicles were replaced.")
        String message,
        @Schema(example = "success") String status,
        @Schema(description = "Number of delivery orders generated and stored", example = "5000")
        int ordersGenerated,
        @Schema(description = "Number of vehicles generated and stored", example = "100")
        int vehiclesGenerated,
        @Schema(description = "Random seed used; the same seed always produces the same data", example = "42")
        long seed,
        @Schema(description = "Time to generate, clear and store the data, in milliseconds", example = "850")
        long elapsedMs) {

    public static final String GENERATED_MESSAGE =
            "Test data generated. All existing orders and vehicles were replaced.";

    /** A success response with the standard message. */
    public static TestDataResponse success(int ordersGenerated, int vehiclesGenerated, long seed, long elapsedMs) {
        return new TestDataResponse(GENERATED_MESSAGE, StatusResponse.SUCCESS, ordersGenerated,
                vehiclesGenerated, seed, elapsedMs);
    }
}
