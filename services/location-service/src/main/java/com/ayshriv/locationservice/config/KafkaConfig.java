package com.ayshriv.locationservice.config;

import com.ayshriv.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    public static final String RIDE_CLOSED_DLT = KafkaTopics.RIDE_CLOSED + ".DLT";

    /**
     * The dead-letter recoverer keeps the original partition number, so the DLT needs at least as many
     * partitions as ride.closed (3, declared by ride-service).
     */
    @Bean
    public NewTopic rideClosedDltTopic() {
        return TopicBuilder.name(RIDE_CLOSED_DLT).partitions(3).replicas(1).build();
    }

    /** Retries a failing record 3 times, 1s apart, then publishes it to ride.closed.DLT. */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> jsonTemplate, KafkaProperties properties) {
        // Records that failed deserialization are still raw bytes and must not go through JsonSerializer.
        // This template is deliberately not a bean: a KafkaTemplate bean would disable Boot's default one.
        Map<String, Object> config = new LinkedHashMap<>(properties.buildProducerProperties(null));
        config.put("key.serializer", StringSerializer.class);
        config.put("value.serializer", ByteArraySerializer.class);
        KafkaTemplate<String, byte[]> bytesTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));

        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, jsonTemplate);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                (record, ex) -> new TopicPartition(RIDE_CLOSED_DLT, record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
