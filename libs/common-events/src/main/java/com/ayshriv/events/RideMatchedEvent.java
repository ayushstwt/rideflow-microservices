package com.ayshriv.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Published when a driver has been matched to a ride.
 * <p>
 * Produced by matching-service, consumed by ride-service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class RideMatchedEvent {

    @NotNull
    private String rideId;
    @NotNull
    private String riderId;
    @NotNull
    private String driverId;

    private Double driverLatitude;
    private Double driverLongitude;
    private Double distanceToPickupKm;

    private Instant matchedAt;
}
