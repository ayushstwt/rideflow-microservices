package com.ayshriv.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Published when no driver could be matched to a ride (e.g. none nearby).
 * <p>
 * Produced by matching-service, consumed by ride-service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class RideMatchFailedEvent {

    @NotNull
    private Long rideId;
    @NotNull
    private Long riderId;

    private String reason;

    private Instant failedAt;
}
