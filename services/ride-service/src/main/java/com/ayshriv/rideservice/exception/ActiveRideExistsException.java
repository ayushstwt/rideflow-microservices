package com.ayshriv.rideservice.exception;

import lombok.Getter;

/** The rider already has a ride that is neither finished nor cancelled. */
@Getter
public class ActiveRideExistsException extends RuntimeException {

    private final String rideId;

    public ActiveRideExistsException(String riderId, String rideId) {
        super("Rider %s already has an active ride: %s".formatted(riderId, rideId));
        this.rideId = rideId;
    }
}
