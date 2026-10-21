package com.rideshare.rideservice.exception;

/** The request is well-formed but not acceptable, e.g. identical pickup and drop. */
public class InvalidRideRequestException extends RuntimeException {

    public InvalidRideRequestException(String message) {
        super(message);
    }
}
