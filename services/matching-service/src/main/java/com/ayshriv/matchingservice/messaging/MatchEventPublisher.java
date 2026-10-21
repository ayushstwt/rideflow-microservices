package com.ayshriv.matchingservice.messaging;

import com.ayshriv.events.KafkaTopics;
import com.ayshriv.events.RideMatchFailedEvent;
import com.ayshriv.events.RideMatchedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** Keyed by rideId so all events of one ride stay ordered in one partition. */
    public void publishMatched(RideMatchedEvent event) {
        send(KafkaTopics.RIDE_MATCHED, event.getRideId(), event);
    }

    public void publishMatchFailed(RideMatchFailedEvent event) {
        send(KafkaTopics.RIDE_MATCH_FAILED, event.getRideId(), event);
    }

    private void send(String topic, String rideId, Object event) {
        kafkaTemplate.send(topic, rideId, event).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Ride {}: failed to publish to {}", rideId, topic, error);
            } else {
                log.info("Ride {}: published to {}-{} @ offset {}", rideId,
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
