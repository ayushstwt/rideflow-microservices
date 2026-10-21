package com.ayshriv.rideservice.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a ride. COMPLETED, CANCELLED and NO_DRIVER_FOUND are terminal.
 */
public enum RideStatus {
    REQUESTED,
    MATCHING,
    ACCEPTED,
    DRIVER_ARRIVING,
    RIDE_STARTED,
    COMPLETED,
    CANCELLED,
    NO_DRIVER_FOUND;

    public boolean canTransitionTo(RideStatus next) {
        return allowedTransitions().contains(next);
    }

    private Set<RideStatus> allowedTransitions() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(MATCHING, CANCELLED);
            case MATCHING -> EnumSet.of(ACCEPTED, NO_DRIVER_FOUND, CANCELLED);
            case ACCEPTED -> EnumSet.of(DRIVER_ARRIVING, RIDE_STARTED, CANCELLED);
            case DRIVER_ARRIVING -> EnumSet.of(RIDE_STARTED, CANCELLED);
            case RIDE_STARTED -> EnumSet.of(COMPLETED);
            case COMPLETED, CANCELLED, NO_DRIVER_FOUND -> EnumSet.noneOf(RideStatus.class);
        };
    }
}
