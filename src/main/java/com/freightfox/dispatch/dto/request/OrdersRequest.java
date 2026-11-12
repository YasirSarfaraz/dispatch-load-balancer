package com.freightfox.dispatch.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Body of POST /api/dispatch/orders. */
@Schema(description = "A batch of delivery orders to upsert")
public record OrdersRequest(
        @NotEmpty
        List<@NotNull @Valid OrderRequest> orders) {
}
