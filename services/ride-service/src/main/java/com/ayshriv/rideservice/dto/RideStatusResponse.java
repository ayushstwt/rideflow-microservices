package com.ayshriv.rideservice.dto;

import com.ayshriv.rideservice.model.RideStatus;

/** Lightweight view for rider apps that only poll for the current status. */
public record RideStatusResponse(String rideId, RideStatus status, String driverId) {
}
