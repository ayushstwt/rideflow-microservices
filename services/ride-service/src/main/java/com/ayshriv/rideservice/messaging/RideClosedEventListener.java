package com.ayshriv.rideservice.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class RideClosedEventListener {

    private final RideEventPublisher publisher;

    /** Runs only once the DB transaction has committed; a rollback means the driver is not released. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRideClosed(RideClosedInternalEvent internal) {
        try {
            publisher.publishRideClosed(internal.event());
        } catch (RuntimeException e) {
            // The ride is already committed; location-service's reservation TTL frees the driver eventually.
            log.error("Ride {}: could not hand ride.closed to Kafka", internal.event().getRideId(), e);
        }
    }
}
