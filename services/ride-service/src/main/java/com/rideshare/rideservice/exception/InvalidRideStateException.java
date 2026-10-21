package com.rideshare.rideservice.exception;

import com.rideshare.rideservice.model.RideStatus;

public class InvalidRideStateException extends RuntimeException {

    public InvalidRideStateException(String message) {
        super(message);
    }

    public InvalidRideStateException(String rideId, RideStatus from, RideStatus to) {
        super("Ride %s cannot move from %s to %s".formatted(rideId, from, to));
    }
}
