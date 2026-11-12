package com.freightfox.dispatch.dto.response;

import com.freightfox.dispatch.entity.Priority;
import io.swagger.v3.oas.annotations.media.Schema;

/** An order as shown in a dispatch plan. */
@Schema(description = "An assigned order")
public record OrderResponse(
        @Schema(example = "ORD001") String orderId,
        @Schema(example = "12.9716") double latitude,
        @Schema(example = "77.5946") double longitude,
        @Schema(example = "MG Road, Bangalore, Karnataka, India") String address,
        @Schema(example = "10") int packageWeight,
        @Schema(example = "HIGH") Priority priority) {
}
