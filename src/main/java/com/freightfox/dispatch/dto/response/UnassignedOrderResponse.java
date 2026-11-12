package com.freightfox.dispatch.dto.response;

import com.freightfox.dispatch.entity.Priority;
import io.swagger.v3.oas.annotations.media.Schema;

/** An order that could not be assigned, with the reason. */
@Schema(description = "An order that could not be assigned")
public record UnassignedOrderResponse(
        @Schema(example = "ORD030") String orderId,
        @Schema(example = "28.7041") double latitude,
        @Schema(example = "77.1025") double longitude,
        @Schema(example = "Karol Bagh, Delhi, India") String address,
        @Schema(example = "20") int packageWeight,
        @Schema(example = "LOW") Priority priority,
        @Schema(example = "Insufficient remaining capacity across fleet") String reason) {
}
