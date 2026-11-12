package com.freightfox.dispatch.dto.request;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** One delivery order in a POST /api/dispatch/orders request. */
@Schema(description = "A delivery order")
public record OrderRequest(
        @Schema(description = "Unique order ID (upsert key)", example = "ORD001")
        @NotBlank @Size(max = DeliveryOrder.MAX_ID_LENGTH)
        String orderId,

        @Schema(description = "Latitude in decimal degrees, -90 to 90", example = "12.9716")
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
        Double latitude,

        @Schema(description = "Longitude in decimal degrees, -180 to 180", example = "77.5946")
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
        Double longitude,

        @Schema(description = "Delivery address", example = "MG Road, Bangalore, Karnataka, India")
        @NotBlank @Size(max = DeliveryOrder.MAX_ADDRESS_LENGTH)
        String address,

        @Schema(description = "Package weight in whole units (e.g. kg); must be > 0", example = "10")
        @NotNull @Positive
        Integer packageWeight,

        @Schema(description = "Delivery priority", example = "HIGH", allowableValues = {"HIGH", "MEDIUM", "LOW"})
        @NotNull
        Priority priority) {
}
