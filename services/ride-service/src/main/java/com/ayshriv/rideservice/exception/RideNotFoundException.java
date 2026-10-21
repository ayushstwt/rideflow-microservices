package com.ayshriv.rideservice.exception;

public class RideNotFoundException extends RuntimeException {

    public RideNotFoundException(String rideId) {
        super("Ride not found: " + rideId);
    }
}
