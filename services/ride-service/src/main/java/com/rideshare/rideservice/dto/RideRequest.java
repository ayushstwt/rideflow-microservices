package com.rideshare.rideservice.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
    private String riderId;

    @NotNull(message = "pickupLatitude is required")
    @DecimalMin(value = "-90.0", message = "pickupLatitude must be >= -90")
    @DecimalMax(value = "90.0", message = "pickupLatitude must be <= 90")
    private Double pickupLatitude;

    @NotNull(message = "pickupLongitude is required")
    @DecimalMin(value = "-180.0", message = "pickupLongitude must be >= -180")
    @DecimalMax(value = "180.0", message = "pickupLongitude must be <= 180")
    private Double pickupLongitude;

    @NotBlank(message = "pickupAddress must not be blank")
    @Size(max = 255, message = "pickupAddress must be at most 255 characters")
    private String pickupAddress;

    @NotNull(message = "dropLatitude is required")
    @DecimalMin(value = "-90.0", message = "dropLatitude must be >= -90")
    @DecimalMax(value = "90.0", message = "dropLatitude must be <= 90")
    private Double dropLatitude;

    @NotNull(message = "dropLongitude is required")
    @DecimalMin(value = "-180.0", message = "dropLongitude must be >= -180")
    @DecimalMax(value = "180.0", message = "dropLongitude must be <= 180")
    private Double dropLongitude;

    @NotBlank(message = "dropAddress must not be blank")
    @Size(max = 255, message = "dropAddress must be at most 255 characters")
    private String dropAddress;
}
