package com.ayshriv.rideservice.service;

import com.ayshriv.rideservice.dto.RideResponse;

/** Outcome of a ride request: {@code created} is false when an earlier request with the same Idempotency-Key is replayed. */
public record RideRequestResult(RideResponse ride, boolean created) {
}
