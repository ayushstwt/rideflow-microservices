package com.ayshriv.matchingservice.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads everything currently on a topic, from the beginning, with a throw-away consumer.
 * Tests call it inside Awaitility, so it never waits for "more" records itself: it stops at the end offsets
 * that existed when it started.
 */
public final class TestKafkaReader {

    public record Rec<T>(String key, T value) {
    }

    private static final Duration MAX_READ_TIME = Duration.ofSeconds(10);

    private final String brokers;
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public TestKafkaReader(String brokers) {
        this.brokers = brokers;
    }

    public <T> List<Rec<T>> read(String topic, Class<T> type) {
        List<Rec<T>> result = new ArrayList<>();
        for (ConsumerRecord<String, String> record : readRaw(topic)) {
            try {
                result.add(new Rec<>(record.key(), mapper.readValue(record.value(), type)));
            } catch (Exception e) {
                throw new IllegalStateException("Record on " + topic + " is not a " + type.getSimpleName() + ": " + record.value(), e);
            }
        }
        return result;
    }

    public List<ConsumerRecord<String, String>> readRaw(String topic) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            Instant deadline = Instant.now().plus(MAX_READ_TIME);
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Could not read " + topic + " up to its end offsets in " + MAX_READ_TIME);
                }
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        return records;
    }
}
