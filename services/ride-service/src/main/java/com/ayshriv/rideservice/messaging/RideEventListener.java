package com.ayshriv.rideservice.messaging;

import com.ayshriv.events.KafkaTopics;
import com.ayshriv.events.RideMatchFailedEvent;
import com.ayshriv.events.RideMatchedEvent;
import com.ayshriv.rideservice.service.OptimisticLockRetry;
import com.ayshriv.rideservice.service.RideService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the matching results. Unknown rides and rides no longer in MATCHING are ignored by the service
 * (no exception, so no pointless redelivery); real failures propagate to the container error handler,
 * which retries and then dead-letters the record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RideEventListener {

    private final RideService rideService;

    @KafkaListener(topics = KafkaTopics.RIDE_MATCHED, containerFactory = "rideMatchedFactory")
    public void onRideMatched(RideMatchedEvent event) {
        log.info("Ride {}: received ride.matched for driver {}", event.getRideId(), event.getDriverId());
        OptimisticLockRetry.run("Ride " + event.getRideId() + " driver assignment",
                () -> rideService.assignDriver(event.getRideId(), event.getDriverId()));
    }

    @KafkaListener(topics = KafkaTopics.RIDE_MATCH_FAILED, containerFactory = "rideMatchFailedFactory")
    public void onRideMatchFailed(RideMatchFailedEvent event) {
        log.info("Ride {}: received ride.match-failed ({})", event.getRideId(), event.getReason());
        OptimisticLockRetry.run("Ride " + event.getRideId() + " no-driver result",
                () -> rideService.markNoDriverFound(event.getRideId(), event.getReason()));
    }
}
