package com.ayshriv.locationservice.exception;

/** Every candidate driver is already reserved for another ride. */
public class NoDriverAvailableException extends RuntimeException {

    public NoDriverAvailableException(String rideId) {
        super("No candidate driver is available for ride " + rideId);
    }
}
