package com.ayshriv.rideservice.config;

import com.ayshriv.events.RideMatchFailedEvent;
import com.ayshriv.events.RideMatchedEvent;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One container factory per event type, because the two topics carry different payloads and a single
 * default type would deserialize one of them wrongly.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final String TRUSTED_PACKAGE = "com.ayshriv.events";

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, RideMatchedEvent> rideMatchedFactory(
            KafkaProperties properties, KafkaTemplate<String, Object> jsonTemplate) {
        return factory(RideMatchedEvent.class, properties, jsonTemplate);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, RideMatchFailedEvent> rideMatchFailedFactory(
            KafkaProperties properties, KafkaTemplate<String, Object> jsonTemplate) {
        return factory(RideMatchFailedEvent.class, properties, jsonTemplate);
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> factory(
            Class<T> type, KafkaProperties properties, KafkaTemplate<String, Object> jsonTemplate) {
        JsonDeserializer<T> json = new JsonDeserializer<>(type, false);
        json.addTrustedPackages(TRUSTED_PACKAGE);

        ConcurrentKafkaListenerContainerFactory<String, T> factory = new ConcurrentKafkaListenerContainerFactory<>();
        // BUG FIX (found by RideFlowIntegrationTest): the JsonDeserializer above is already configured in code, and
        // Spring Kafka refuses a deserializer that is configured both ways, so the container could never start.
        // The spring.json.* / spring.deserializer.* properties from application.yml are therefore left out here.
        Map<String, Object> consumerProps = new LinkedHashMap<>(properties.buildConsumerProperties(null));
        consumerProps.keySet().removeIf(key -> key.startsWith("spring.json.") || key.startsWith("spring.deserializer."));
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new ErrorHandlingDeserializer<>(json)));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(errorHandler(properties, jsonTemplate));
        return factory;
    }

    /**
     * Retries a failing record 3 times, 1s apart, then publishes it to {topic}.DLT. Records that failed
     * deserialization are still raw bytes and must not go through JsonSerializer, hence the second template
     * (deliberately not a bean: a KafkaTemplate bean would disable Boot's default one).
     */
    private DefaultErrorHandler errorHandler(KafkaProperties properties, KafkaTemplate<String, Object> jsonTemplate) {
        Map<String, Object> config = new LinkedHashMap<>(properties.buildProducerProperties(null));
        config.put("key.serializer", StringSerializer.class);
        config.put("value.serializer", ByteArraySerializer.class);
        KafkaTemplate<String, byte[]> bytesTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));

        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, jsonTemplate);

        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(templates), new FixedBackOff(1000L, 3L));
    }
}
