package com.ayshriv.rideservice.messaging;

import com.ayshriv.events.KafkaTopics;
import com.ayshriv.events.RideRequestedEvent;
import com.ayshriv.rideservice.model.Ride;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class RideEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** Keyed by rideId so all events of one ride land in the same partition, in order. */
    public void publishRideRequested(Ride ride) {
        RideRequestedEvent event = RideRequestedEvent.builder()
                .rideId(ride.getId())
                .riderId(ride.getRiderId())
                .pickupLatitude(ride.getPickupLatitude())
                .pickupLongitude(ride.getPickupLongitude())
                .pickupAddress(ride.getPickupAddress())
                .dropLatitude(ride.getDropLatitude())
                .dropLongitude(ride.getDropLongitude())
                .dropAddress(ride.getDropAddress())
                .requestedAt(Instant.now())
                .build();

        kafkaTemplate.send(KafkaTopics.RIDE_REQUESTED, ride.getId(), event)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("Failed to publish ride.requested for ride {}", ride.getId(), error);
                    } else {
                        log.info("Published ride.requested for ride {} to {}-{} @ offset {}",
                                ride.getId(),
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
