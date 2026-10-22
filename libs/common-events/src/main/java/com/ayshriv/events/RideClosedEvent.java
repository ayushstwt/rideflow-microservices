package com.ayshriv.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Published when a ride reaches a terminal state (completed, cancelled, match failed, ...),
 * so the driver held for it can be released.
 * <p>
 * Produced by ride-service, consumed by location-service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class RideClosedEvent {

    @NotNull
    private String rideId;
    @NotNull
    private String riderId;

    /** Null when the ride closed before any driver was assigned. */
    private String driverId;

    /** Name of the terminal ride status, as a plain string to keep this module free of ride-service enums. */
    private String finalStatus;

    private Instant closedAt;
}
