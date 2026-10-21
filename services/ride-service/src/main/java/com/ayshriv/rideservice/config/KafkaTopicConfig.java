package com.ayshriv.rideservice.config;

import com.ayshriv.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service uses so they exist even when broker auto-creation is off.
 */
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final int REPLICAS = 1;

    @Bean
    public NewTopic rideRequestedTopic() {
        return topic(KafkaTopics.RIDE_REQUESTED);
    }

    @Bean
    public NewTopic rideMatchedTopic() {
        return topic(KafkaTopics.RIDE_MATCHED);
    }

    @Bean
    public NewTopic rideMatchFailedTopic() {
        return topic(KafkaTopics.RIDE_MATCH_FAILED);
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(REPLICAS).build();
    }
}
