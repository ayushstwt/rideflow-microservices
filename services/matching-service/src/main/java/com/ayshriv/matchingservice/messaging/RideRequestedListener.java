package com.ayshriv.matchingservice.messaging;

import com.ayshriv.events.KafkaTopics;
import com.ayshriv.events.RideMatchFailedEvent;
import com.ayshriv.events.RideMatchedEvent;
import com.ayshriv.events.RideRequestedEvent;
import com.ayshriv.matchingservice.service.Match;
import com.ayshriv.matchingservice.service.MatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class RideRequestedListener {

    static final String NO_DRIVERS_AVAILABLE = "NO_DRIVERS_AVAILABLE";

    private final MatchingService matchingService;
    private final MatchEventPublisher publisher;

    /** Exceptions propagate to the container error handler (retry, then ride.requested.DLT). */
    @KafkaListener(topics = KafkaTopics.RIDE_REQUESTED)
    public void onRideRequested(RideRequestedEvent event) {
        log.info("Ride {}: received ride.requested", event.getRideId());

        Optional<Match> match = matchingService.findMatch(event);
        if (match.isPresent()) {
            Match m = match.get();
            publisher.publishMatched(RideMatchedEvent.builder()
                    .rideId(event.getRideId())
                    .riderId(event.getRiderId())
                    .driverId(m.driverId())
                    .driverLatitude(m.latitude())
                    .driverLongitude(m.longitude())
                    .distanceToPickupKm(m.distanceKm())
                    .matchedAt(Instant.now())
                    .build());
        } else {
            log.warn("Ride {}: no driver found, publishing match failure", event.getRideId());
            publisher.publishMatchFailed(RideMatchFailedEvent.builder()
                    .rideId(event.getRideId())
                    .riderId(event.getRiderId())
                    .reason(NO_DRIVERS_AVAILABLE)
                    .failedAt(Instant.now())
                    .build());
        }
    }
}
