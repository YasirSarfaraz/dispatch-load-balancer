package com.freightfox.dispatch.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Body of POST /api/dispatch/vehicles. */
@Schema(description = "A batch of vehicles to upsert")
public record VehiclesRequest(
        @NotEmpty
        List<@NotNull @Valid VehicleRequest> vehicles) {
}
