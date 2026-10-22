package com.ayshriv.rideservice.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Wrapper types are used for coordinates because {@code @NotNull} has no effect on primitives. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RideRequest {

    @NotBlank(message = "riderId must not be blank")
    @Size(max = 255, message = "riderId must be at most 255 characters")
    @Schema(description = "Id of the rider requesting the ride (max 255 characters)", example = "rider-lko-42")
    private String riderId;

    @NotNull(message = "pickupLatitude is required")
    @DecimalMin(value = "-90.0", message = "pickupLatitude must be >= -90")
    @DecimalMax(value = "90.0", message = "pickupLatitude must be <= 90")
    @Schema(description = "Pickup latitude in degrees (-90 to 90)", example = "26.8467")
    private Double pickupLatitude;

    @NotNull(message = "pickupLongitude is required")
    @DecimalMin(value = "-180.0", message = "pickupLongitude must be >= -180")
    @DecimalMax(value = "180.0", message = "pickupLongitude must be <= 180")
    @Schema(description = "Pickup longitude in degrees (-180 to 180)", example = "80.9462")
    private Double pickupLongitude;

    @NotBlank(message = "pickupAddress must not be blank")
    @Size(max = 255, message = "pickupAddress must be at most 255 characters")
    @Schema(description = "Human-readable pickup address (max 255 characters)", example = "Hazratganj, Lucknow")
    private String pickupAddress;

    @NotNull(message = "dropLatitude is required")
    @DecimalMin(value = "-90.0", message = "dropLatitude must be >= -90")
    @DecimalMax(value = "90.0", message = "dropLatitude must be <= 90")
    @Schema(description = "Drop-off latitude in degrees (-90 to 90); the drop point must differ from the pickup point", example = "26.7606")
    private Double dropLatitude;

    @NotNull(message = "dropLongitude is required")
    @DecimalMin(value = "-180.0", message = "dropLongitude must be >= -180")
    @DecimalMax(value = "180.0", message = "dropLongitude must be <= 180")
    @Schema(description = "Drop-off longitude in degrees (-180 to 180)", example = "80.8893")
    private Double dropLongitude;

    @NotBlank(message = "dropAddress must not be blank")
    @Size(max = 255, message = "dropAddress must be at most 255 characters")
    @Schema(description = "Human-readable drop-off address (max 255 characters)", example = "Chaudhary Charan Singh Airport, Lucknow")
    private String dropAddress;
}
