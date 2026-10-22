package com.ayshriv.locationservice.messaging;

import com.ayshriv.events.KafkaTopics;
import com.ayshriv.events.RideClosedEvent;
import com.ayshriv.locationservice.service.LocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RideClosedListener {

    private final LocationService locationService;

    /**
     * Releasing is idempotent and a no-op for an unknown ride, so duplicates and events for rides that
     * never had a driver are harmless. Redis failures propagate to the container error handler.
     */
    @KafkaListener(topics = KafkaTopics.RIDE_CLOSED)
    public void onRideClosed(RideClosedEvent event) {
        if (event == null || event.getRideId() == null) {
            log.warn("Ignoring ride.closed event without rideId: {}", event);
            return;
        }
        log.info("Ride {}: closed as {}, releasing driver", event.getRideId(), event.getFinalStatus());
        locationService.releaseDriver(event.getRideId());
    }
}
