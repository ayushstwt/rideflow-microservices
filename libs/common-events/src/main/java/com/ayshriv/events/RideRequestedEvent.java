package com.ayshriv.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Published when a rider requests a ride.
 * <p>
 * Produced by ride-service, consumed by matching-service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class RideRequestedEvent {

    @NotNull
    private String rideId;
    @NotNull
    private String riderId;

    private Double pickupLatitude;
    private Double pickupLongitude;
    private String pickupAddress;

    private Double dropLatitude;
    private Double dropLongitude;
    private String dropAddress;

    private Instant requestedAt;

    /** Matching attempt number, starting at 1; older messages without this field default to 1. */
    @Builder.Default
    private int attempt = 1;
}
