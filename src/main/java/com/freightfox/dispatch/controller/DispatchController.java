package com.freightfox.dispatch.controller;

import com.freightfox.dispatch.dto.request.OrdersRequest;
import com.freightfox.dispatch.dto.request.VehiclesRequest;
import com.freightfox.dispatch.dto.response.DispatchPlanResponse;
import com.freightfox.dispatch.dto.response.ErrorResponse;
import com.freightfox.dispatch.dto.response.StatusResponse;
import com.freightfox.dispatch.mapper.RequestMapper;
import com.freightfox.dispatch.service.DispatchService;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.VehicleService;
import com.freightfox.dispatch.strategy.StrategyNames;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API of the Dispatch Load Balancer. Deliberately thin: validates and maps the request, then
 * delegates to the services. Strategy selection happens in the service through the registry, so
 * there is no branching on the strategy here.
 */
@RestController
@RequestMapping(path = ApiMessages.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = ApiMessages.TAG, description = ApiMessages.TAG_DESCRIPTION)
public class DispatchController {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String OK = "200";
    private static final String BAD_REQUEST = "400";

    private final OrderService orderService;
    private final VehicleService vehicleService;
    private final DispatchService dispatchService;
    private final RequestMapper requestMapper;

    public DispatchController(OrderService orderService, VehicleService vehicleService,
                              DispatchService dispatchService, RequestMapper requestMapper) {
        this.orderService = orderService;
        this.vehicleService = vehicleService;
        this.dispatchService = dispatchService;
        this.requestMapper = requestMapper;
    }

    @PostMapping(path = ApiMessages.ORDERS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Upload delivery orders (upsert)",
            description = "Inserts new orders and updates existing ones matched by orderId. Accepts large "
                    + "batches (tested with 50,000 orders); rows are written in chunks with JDBC batching. "
                    + "The whole batch is validated first and is rejected if any order is invalid or an "
                    + "orderId occurs twice.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = JSON,
            schema = @Schema(implementation = OrdersRequest.class),
            examples = {
                    @ExampleObject(name = OpenApiExamples.ASSIGNMENT, value = OpenApiExamples.ORDERS_REQUEST),
                    @ExampleObject(name = OpenApiExamples.SAMPLE_DATASET, value = OpenApiExamples.ORDERS_SAMPLE_DATASET)
            }))
    @ApiResponse(responseCode = OK, description = "Orders stored", content = @Content(mediaType = JSON,
            schema = @Schema(implementation = StatusResponse.class),
            examples = @ExampleObject(value = OpenApiExamples.ORDERS_ACCEPTED_RESPONSE)))
    @ApiResponse(responseCode = BAD_REQUEST, description = "Invalid input: validation error, invalid priority, "
            + "duplicate orderId or malformed JSON", content = @Content(mediaType = JSON,
            schema = @Schema(implementation = ErrorResponse.class),
            examples = {
                    @ExampleObject(name = "Validation error", value = OpenApiExamples.VALIDATION_ERROR),
                    @ExampleObject(name = "Invalid priority", value = OpenApiExamples.INVALID_PRIORITY_ERROR),
                    @ExampleObject(name = "Duplicate orderId", value = OpenApiExamples.DUPLICATE_ORDER_ERROR),
                    @ExampleObject(name = "Malformed JSON", value = OpenApiExamples.MALFORMED_JSON_ERROR)
            }))
    public StatusResponse addOrders(@Valid @RequestBody OrdersRequest request) {
        orderService.upsertOrders(requestMapper.toOrders(request));
        return StatusResponse.success(ApiMessages.ORDERS_ACCEPTED);
    }

    @PostMapping(path = ApiMessages.VEHICLES_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Upload vehicles (upsert)",
            description = "Inserts new vehicles and updates existing ones matched by vehicleId. The whole "
                    + "batch is validated first and is rejected if any vehicle is invalid or a vehicleId "
                    + "occurs twice.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = JSON,
            schema = @Schema(implementation = VehiclesRequest.class),
            examples = {
                    @ExampleObject(name = OpenApiExamples.ASSIGNMENT, value = OpenApiExamples.VEHICLES_REQUEST),
                    @ExampleObject(name = OpenApiExamples.SAMPLE_DATASET, value = OpenApiExamples.VEHICLES_SAMPLE_DATASET)
            }))
    @ApiResponse(responseCode = OK, description = "Vehicles stored", content = @Content(mediaType = JSON,
            schema = @Schema(implementation = StatusResponse.class),
            examples = @ExampleObject(value = OpenApiExamples.VEHICLES_ACCEPTED_RESPONSE)))
    @ApiResponse(responseCode = BAD_REQUEST, description = "Invalid input: validation error, duplicate "
            + "vehicleId or malformed JSON", content = @Content(mediaType = JSON,
            schema = @Schema(implementation = ErrorResponse.class),
            examples = {
                    @ExampleObject(name = "Validation error", value = OpenApiExamples.VEHICLE_VALIDATION_ERROR),
                    @ExampleObject(name = "Duplicate vehicleId", value = OpenApiExamples.DUPLICATE_VEHICLE_ERROR),
                    @ExampleObject(name = "Malformed JSON", value = OpenApiExamples.MALFORMED_JSON_ERROR)
            }))
    public StatusResponse addVehicles(@Valid @RequestBody VehiclesRequest request) {
        vehicleService.upsertVehicles(requestMapper.toVehicles(request));
        return StatusResponse.success(ApiMessages.VEHICLES_ACCEPTED);
    }

    @GetMapping(ApiMessages.PLAN_PATH)
    @Operation(summary = "Compute the dispatch plan",
            description = "Assigns the stored orders to the stored vehicles without exceeding any capacity, "
                    + "HIGH priority first, then MEDIUM, then LOW, minimising Haversine travel distance. Every "
                    + "vehicle is listed (unused ones with totalLoad 0 and \"0.00 km\"); assignedOrders are in "
                    + "visiting order; totalDistance is the open route from the vehicle's location through its "
                    + "stops (no return trip). Orders that cannot be placed are listed in unassignedOrders with "
                    + "a reason. The plan is computed on demand and not stored.")
    @ApiResponse(responseCode = OK, description = "Dispatch plan", content = @Content(mediaType = JSON,
            schema = @Schema(implementation = DispatchPlanResponse.class),
            examples = @ExampleObject(name = OpenApiExamples.ASSIGNMENT, value = OpenApiExamples.PLAN_RESPONSE)))
    @ApiResponse(responseCode = BAD_REQUEST, description = "Unknown strategy or no vehicles stored",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class),
                    examples = {
                            @ExampleObject(name = "No vehicles", value = OpenApiExamples.NO_VEHICLES_ERROR),
                            @ExampleObject(name = "Unknown strategy", value = OpenApiExamples.UNKNOWN_STRATEGY_ERROR)
                    }))
    public DispatchPlanResponse getPlan(
            @Parameter(description = "Algorithm: greedy (fast heuristic, default) or ortools (Google OR-Tools "
                    + "CVRP solver; falls back to greedy for large inputs). Case-insensitive.",
                    schema = @Schema(allowableValues = {StrategyNames.GREEDY, StrategyNames.ORTOOLS},
                            defaultValue = StrategyNames.DEFAULT))
            @RequestParam(name = ApiMessages.STRATEGY_PARAM, required = false) String strategy) {
        return dispatchService.generatePlan(strategy);
    }

    @DeleteMapping(ApiMessages.ORDERS_PATH)
    @Operation(summary = "Delete all delivery orders", description = "Removes every stored order (one DELETE statement).")
    @ApiResponse(responseCode = OK, description = "Orders deleted", content = @Content(mediaType = JSON,
            schema = @Schema(implementation = StatusResponse.class),
            examples = @ExampleObject(value = OpenApiExamples.ORDERS_DELETED_RESPONSE)))
    public StatusResponse deleteOrders() {
        orderService.deleteAllOrders();
        return StatusResponse.success(ApiMessages.ORDERS_DELETED);
    }

    @DeleteMapping(ApiMessages.VEHICLES_PATH)
    @Operation(summary = "Delete all vehicles", description = "Removes every stored vehicle (one DELETE statement).")
    @ApiResponse(responseCode = OK, description = "Vehicles deleted", content = @Content(mediaType = JSON,
            schema = @Schema(implementation = StatusResponse.class),
            examples = @ExampleObject(value = OpenApiExamples.VEHICLES_DELETED_RESPONSE)))
    public StatusResponse deleteVehicles() {
        vehicleService.deleteAllVehicles();
        return StatusResponse.success(ApiMessages.VEHICLES_DELETED);
    }
}
