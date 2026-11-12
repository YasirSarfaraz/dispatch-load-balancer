package com.freightfox.dispatch.controller;

import com.freightfox.dispatch.dto.response.ErrorResponse;
import com.freightfox.dispatch.dto.response.TestDataResponse;
import com.freightfox.dispatch.testdata.TestDataResult;
import com.freightfox.dispatch.testdata.TestDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo/testing endpoint that fills the database with a generated, reproducible dataset.
 * Registered only when {@code app.test-data.enabled=true} (the default).
 */
@RestController
@RequestMapping(TestDataController.BASE_PATH)
@ConditionalOnProperty(prefix = "app.test-data", name = "enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Test data (demo)",
        description = "Demo/testing only. Generates a reproducible dataset around 30 Indian cities and "
                + "REPLACES all stored orders and vehicles with it. Disable with app.test-data.enabled=false.")
public class TestDataController {

    public static final String BASE_PATH = "/api/dispatch";
    public static final String TEST_DATA_PATH = "/test-data";
    public static final String DEFAULT_ORDERS = "5000";
    public static final String DEFAULT_VEHICLES = "100";
    public static final String DEFAULT_SEED = "42";

    private static final String BAD_REQUEST_EXAMPLE = """
            {"status": "error", "message": "orders must be between 1 and 50000",
             "errors": ["orders: must be between 1 and 50000"], "timestamp": "2026-11-12T10:15:30.123Z"}""";

    private final TestDataService testDataService;

    public TestDataController(TestDataService testDataService) {
        this.testDataService = testDataService;
    }

    @PostMapping(value = TEST_DATA_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate test data (demo; replaces all orders and vehicles)",
            description = """
                    Demo/testing endpoint. Deletes ALL stored orders and vehicles, then generates \
                    `orders` orders and `vehicles` vehicles placed up to ~15 km around 30 real Indian \
                    city centres and stores them through the same chunked upsert used by \
                    POST /orders and POST /vehicles. Priority mix 30% HIGH / 40% MEDIUM / 30% LOW, \
                    package weight 1-50, capacity 100-500, IDs ORD000001.. and VEH0001... \
                    The same seed always produces the same data. Call GET /api/dispatch/plan \
                    afterwards to plan the generated dataset.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Data generated and stored",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TestDataResponse.class))),
            @ApiResponse(responseCode = "400", description = "orders or vehicles out of range, or not a number",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = BAD_REQUEST_EXAMPLE)))
    })
    public TestDataResponse generateTestData(
            @Parameter(description = "Number of orders to generate (1 to app.test-data.max-orders, default max 50000)",
                    example = DEFAULT_ORDERS)
            @RequestParam(defaultValue = DEFAULT_ORDERS) int orders,
            @Parameter(description = "Number of vehicles to generate (1 to app.test-data.max-vehicles, default max 2000)",
                    example = DEFAULT_VEHICLES)
            @RequestParam(defaultValue = DEFAULT_VEHICLES) int vehicles,
            @Parameter(description = "Random seed; the same seed always produces the same dataset",
                    example = DEFAULT_SEED)
            @RequestParam(defaultValue = DEFAULT_SEED) long seed) {
        TestDataResult result = testDataService.replaceWithGeneratedData(orders, vehicles, seed);
        return TestDataResponse.success(result.ordersGenerated(), result.vehiclesGenerated(),
                result.seed(), result.elapsedMs());
    }
}
