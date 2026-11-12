package com.freightfox.dispatch.dto.request;

import com.freightfox.dispatch.entity.Vehicle;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** One vehicle in a POST /api/dispatch/vehicles request. */
@Schema(description = "A vehicle of the fleet")
public record VehicleRequest(
        @Schema(description = "Unique vehicle ID (upsert key)", example = "VEH001")
        @NotBlank @Size(max = Vehicle.MAX_ID_LENGTH)
        String vehicleId,

        @Schema(description = "Maximum load (same unit as packageWeight); must be > 0", example = "100")
        @NotNull @Positive
        Integer capacity,

        @Schema(description = "Current latitude, -90 to 90", example = "12.9716")
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
        Double currentLatitude,

        @Schema(description = "Current longitude, -180 to 180", example = "77.6413")
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
        Double currentLongitude,

        @Schema(description = "Current address", example = "Indiranagar, Bangalore, Karnataka, India")
        @NotBlank @Size(max = Vehicle.MAX_ADDRESS_LENGTH)
        String currentAddress) {
}
