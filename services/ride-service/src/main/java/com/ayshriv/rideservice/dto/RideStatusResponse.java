package com.ayshriv.rideservice.dto;

import com.ayshriv.rideservice.model.RideStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** Lightweight view for rider apps that only poll for the current status. */
public record RideStatusResponse(
        @Schema(description = "Ride id (UUID)", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") String rideId,
        @Schema(description = "Current status", example = "ACCEPTED") RideStatus status,
        @Schema(description = "Assigned driver; null until a driver is matched", example = "driver-lko-001") String driverId) {
}
