package com.ayshriv.events;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Kafka topic names shared by all services so producers and consumers never drift apart.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KafkaTopics {

    /** Produced by ride-service, consumed by matching-service. */
    public static final String RIDE_REQUESTED = "ride.requested";

    /** Produced by matching-service, consumed by ride-service. */
    public static final String RIDE_MATCHED = "ride.matched";

    /** Produced by matching-service, consumed by ride-service. */
    public static final String RIDE_MATCH_FAILED = "ride.match-failed";
}
