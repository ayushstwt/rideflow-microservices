package com.ayshriv.rideservice.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class RideRequestedEventListener {

    private final RideEventPublisher publisher;

    /** Runs only once the DB transaction has committed; a rollback means nothing is sent. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRideRequested(RideRequestedInternalEvent event) {
        try {
            publisher.publishRideRequested(event.ride());
        } catch (RuntimeException e) {
            // The ride is already committed; do not turn that into an HTTP error for the rider.
            log.error("Ride {}: could not hand ride.requested to Kafka", event.ride().getId(), e);
        }
    }
}
