package com.freightfox.dispatch.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * OpenAPI (Swagger) metadata. Swagger UI: /swagger-ui.html, spec: /v3/api-docs.
 *
 * <p>The only server entry is the relative URL "/", so "Try it out" always calls the host the page
 * was loaded from (localhost, Docker or the deployed HTTPS URL) and never a hardcoded or
 * proxy-rewritten http:// address.
 *
 * <p>Swagger UI shows the groups in the order of {@link #TAGS} and, inside a group, the operations
 * in the order of {@link #PATH_ORDER}: the order an evaluator uses the API (upload orders, upload
 * vehicles, compute the plan), with the destructive calls in a group of their own.
 */
@Configuration
public class OpenApiConfig {

    public static final String DISPATCH_TAG = "Dispatch";
    public static final String RESET_TAG = "Reset";
    public static final String TEST_DATA_TAG = "Test data (demo)";

    static final List<Tag> TAGS = List.of(
            new Tag().name(DISPATCH_TAG)
                    .description("Upload delivery orders and vehicles, then compute an optimized dispatch plan"),
            new Tag().name(RESET_TAG)
                    .description("Delete the stored orders or vehicles to start a test run from an empty database"),
            new Tag().name(TEST_DATA_TAG)
                    .description("Demo/testing only. Generates a reproducible dataset around 30 Indian cities and "
                            + "REPLACES all stored orders and vehicles with it. Disable with "
                            + "app.test-data.enabled=false."));

    static final List<String> PATH_ORDER = List.of(
            "/api/dispatch/orders",
            "/api/dispatch/vehicles",
            "/api/dispatch/plan",
            "/api/dispatch/test-data");

    static final String TITLE = "Dispatch Load Balancer API";
    static final String VERSION = "1.0.0";
    static final String RELATIVE_SERVER_URL = "/";
    static final String SERVER_DESCRIPTION = "This server";
    static final String DESCRIPTION = """
            Optimizes the allocation of delivery orders to a fleet of vehicles (a Capacitated Vehicle \
            Routing Problem).

            **Flow**
            1. `POST /api/dispatch/orders` - upload delivery orders (upsert by orderId).
            2. `POST /api/dispatch/vehicles` - upload vehicles (upsert by vehicleId).
            3. `GET /api/dispatch/plan` - compute the dispatch plan on demand.

            **Rules**: a vehicle's total load never exceeds its capacity; HIGH priority orders are \
            assigned first, then MEDIUM, then LOW; travel distance is minimised. Distances use the \
            Haversine formula (Earth radius 6371 km). Routes are *open*: a vehicle's distance runs from \
            its current location through its stops in visiting order, with no return trip. Orders that \
            cannot be placed are returned in `unassignedOrders` with a reason.

            **Strategies** (`?strategy=`): `greedy` (default; priority-first cheapest insertion followed \
            by nearest-neighbour + 2-opt route improvement) or `ortools` (Google OR-Tools CVRP solver \
            with priority-weighted drop penalties; it falls back to greedy above 1,000 orders, when the \
            solver is unavailable or busy, or when its plan would leave more high-priority orders \
            unassigned than greedy's). The \
            `strategy` field of the response names the algorithm that produced the plan.

            **Errors** all have one shape: \
            `{"status": "error", "message": "...", "errors": ["field: reason"], "timestamp": "..."}`. \
            400 invalid input, unknown strategy or no vehicles · 404 unknown path · 405 wrong method · \
            406 unsupported `Accept` · 409 concurrent conflicting write (retry) · 415 body not JSON · \
            500 unexpected error (no internals are exposed).

            **Operations**: every response carries a `Server-Timing: app;dur=<ms>` header. \
            `GET /actuator/health` reports liveness; `GET /actuator/info` the version, commit and database.
            """;

    @Bean
    public OpenAPI dispatchOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title(TITLE)
                        .version(VERSION)
                        .description(DESCRIPTION))
                .servers(List.of(new Server().url(RELATIVE_SERVER_URL).description(SERVER_DESCRIPTION)))
                .tags(TAGS);
    }

    /** Fixes the presentation order of the generated document; the content itself is unchanged. */
    @Bean
    public OpenApiCustomizer readableOrder() {
        return openApi -> {
            openApi.tags(usedTags(openApi));
            orderPaths(openApi);
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                Map<String, Schema> schemas = new TreeMap<>(openApi.getComponents().getSchemas());
                schemas.values().forEach(OpenApiConfig::requireNonEmptyRequiredStrings);
                openApi.getComponents().setSchemas(schemas);
            }
        };
    }

    /** {@link #TAGS} in order, without a group whose endpoints are switched off (e.g. test data). */
    private static List<Tag> usedTags(OpenAPI openApi) {
        Set<String> used = openApi.getPaths() == null ? Set.of() : openApi.getPaths().values().stream()
                .flatMap(item -> item.readOperations().stream())
                .filter(operation -> operation.getTags() != null)
                .flatMap(operation -> operation.getTags().stream())
                .collect(Collectors.toSet());
        return TAGS.stream().filter(tag -> used.contains(tag.getName())).toList();
    }

    private static void orderPaths(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        Paths ordered = new Paths();
        PATH_ORDER.stream()
                .filter(openApi.getPaths()::containsKey)
                .forEach(path -> ordered.addPathItem(path, openApi.getPaths().get(path)));
        openApi.getPaths().forEach((path, item) -> {
            if (!ordered.containsKey(path)) {
                ordered.addPathItem(path, item);
            }
        });
        openApi.setPaths(ordered);
    }

    /**
     * {@code @Size(max = n)} documents a string as "0..n characters", but every required string in the
     * request DTOs is also {@code @NotBlank}, so the real lower bound is 1.
     */
    private static void requireNonEmptyRequiredStrings(Schema<?> schema) {
        if (schema.getProperties() == null || schema.getRequired() == null) {
            return;
        }
        schema.getRequired().forEach(name -> {
            Schema<?> property = schema.getProperties().get(name);
            if (property != null && Integer.valueOf(0).equals(property.getMinLength())) {
                property.setMinLength(1);
            }
        });
    }
}
