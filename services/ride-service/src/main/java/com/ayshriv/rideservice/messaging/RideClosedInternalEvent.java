package com.ayshriv.rideservice.messaging;

import com.ayshriv.events.RideClosedEvent;

/** In-process event published inside the transaction that closes a ride; Kafka is only touched after commit. */
public record RideClosedInternalEvent(RideClosedEvent event) {
}
