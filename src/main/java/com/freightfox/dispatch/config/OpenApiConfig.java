package com.freightfox.dispatch.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI (Swagger) metadata. Swagger UI: /swagger-ui.html, spec: /v3/api-docs.
 *
 * <p>The only server entry is the relative URL "/", so "Try it out" always calls the host the page
 * was loaded from (localhost, Docker or the deployed HTTPS URL) and never a hardcoded or
 * proxy-rewritten http:// address.
 */
@Configuration
public class OpenApiConfig {

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
            with priority-weighted drop penalties; falls back to greedy for very large inputs).

            Every error has the same shape: \
            `{"status": "error", "message": "...", "errors": ["field: reason"], "timestamp": "..."}`.
            """;

    @Bean
    public OpenAPI dispatchOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title(TITLE)
                        .version(VERSION)
                        .description(DESCRIPTION))
                .servers(List.of(new Server().url(RELATIVE_SERVER_URL).description(SERVER_DESCRIPTION)));
    }
}
