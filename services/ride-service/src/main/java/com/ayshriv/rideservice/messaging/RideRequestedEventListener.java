package com.ayshriv.rideservice.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class RideRequestedEventListener {

    private final RideEventPublisher publisher;

    /** Runs only once the DB transaction has committed; a rollback means nothing is sent. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRideRequested(RideRequestedInternalEvent event) {
        publisher.publishRideRequested(event.ride());
    }
}
