package com.ayshriv.rideservice.messaging;

import com.ayshriv.rideservice.model.Ride;

/** In-process event published inside the request transaction; Kafka is only touched after commit. */
public record RideRequestedInternalEvent(Ride ride) {
}
