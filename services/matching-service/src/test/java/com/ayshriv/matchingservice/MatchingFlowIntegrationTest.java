package com.ayshriv.matchingservice;

import com.ayshriv.events.KafkaTopics;
import com.ayshriv.events.RideMatchFailedEvent;
import com.ayshriv.events.RideMatchedEvent;
import com.ayshriv.events.RideRequestedEvent;
import com.ayshriv.matchingservice.config.KafkaConfig;
import com.ayshriv.matchingservice.support.TestKafkaReader;
import com.ayshriv.matchingservice.support.TestKafkaReader.Rec;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * matching-service end to end inside one JVM: real Spring context and listener, an embedded Kafka broker and
 * WireMock standing in for location-service (real Feign client, circuit breaker and timeouts).
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 3, topics = {
        KafkaTopics.RIDE_REQUESTED, KafkaTopics.RIDE_MATCHED, KafkaTopics.RIDE_MATCH_FAILED})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MatchingFlowIntegrationTest {

    private static final String NEARBY = "/api/v1/locations/drivers/nearby";
    private static final String RESERVE = "/api/v1/locations/drivers/reserve";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final WireMockServer LOCATION_SERVICE = new WireMockServer(options().dynamicPort());

    static {
        LOCATION_SERVICE.start();
    }

    @DynamicPropertySource
    static void locationServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("location-service.url", () -> "http://localhost:" + LOCATION_SERVICE.port());
    }

    @AfterAll
    static void stopWireMock() {
        LOCATION_SERVICE.stop();
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    private TestKafkaReader kafka;

    @BeforeEach
    void setUp() {
        LOCATION_SERVICE.resetAll();
        // Failures in one test must not leave the circuit open for the next one.
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        kafka = new TestKafkaReader(broker.getBrokersAsString());
    }

    // a) ----------------------------------------------------------------------------------------------------

    @Test
    void threeNearbyDrivers_reserveGetsCandidatesBestFirstAndTheReservedDriverIsMatched() {
        stubNearby(threeDrivers());
        stubReserve(okJson("{\"driverId\":\"near\"}"));

        send("ride-a");

        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Rec<RideMatchedEvent>> matched = matchedFor("ride-a");
            assertThat(matched).hasSize(1);
            assertThat(matched.get(0).key()).isEqualTo("ride-a");
            RideMatchedEvent event = matched.get(0).value();
            assertThat(event.getDriverId()).isEqualTo("near");
            assertThat(event.getRiderId()).isEqualTo("rider-ride-a");
            assertThat(event.getDriverLatitude()).isEqualTo(12.971);
            assertThat(event.getDriverLongitude()).isEqualTo(77.591);
            assertThat(event.getDistanceToPickupKm()).isEqualTo(0.4);
            assertThat(event.getMatchedAt()).isNotNull();
        });
        // far / mid / near were returned in this order; the request must be near, mid, far (distance decides).
        LOCATION_SERVICE.verify(1, postRequestedFor(urlPathEqualTo(RESERVE)).withRequestBody(equalToJson(
                "{\"rideId\":\"ride-a\",\"candidateDriverIds\":[\"near\",\"mid\",\"far\"]}")));
        assertThat(failedFor("ride-a")).isEmpty();
    }

    @Test
    void topDriverTakenMeanwhile_theDriverReturnedByTheReservationIsMatched() {
        stubNearby(threeDrivers());
        stubReserve(okJson("{\"driverId\":\"mid\"}"));

        send("ride-a2");

        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Rec<RideMatchedEvent>> matched = matchedFor("ride-a2");
            assertThat(matched).hasSize(1);
            assertThat(matched.get(0).value().getDriverId()).isEqualTo("mid");
            assertThat(matched.get(0).value().getDistanceToPickupKm()).isEqualTo(1.5);
        });
    }

    // b) ----------------------------------------------------------------------------------------------------

    @Test
    void noNearbyDrivers_publishesMatchFailedAndNeverTriesToReserve() {
        stubNearby("[]");

        send("ride-b");

        awaitFailed("ride-b");
        LOCATION_SERVICE.verify(0, postRequestedFor(urlPathEqualTo(RESERVE)));
        assertThat(matchedFor("ride-b")).isEmpty();
    }

    // c) ----------------------------------------------------------------------------------------------------

    @Test
    void reserveAnswers409_publishesMatchFailed() {
        stubNearby(threeDrivers());
        stubReserve(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"NO_DRIVER_AVAILABLE\"}"));

        send("ride-c");

        awaitFailed("ride-c");
        assertThat(matchedFor("ride-c")).isEmpty();
    }

    // d) ----------------------------------------------------------------------------------------------------

    @Test
    void locationServiceAnswers500_fallbackPublishesMatchFailed() {
        LOCATION_SERVICE.stubFor(get(urlPathEqualTo(NEARBY)).willReturn(aResponse().withStatus(500)));

        send("ride-d1");

        awaitFailed("ride-d1");
        assertThat(deadLetters()).noneMatch(record -> record.value().contains("ride-d1"));
    }

    @Test
    void locationServiceTimesOut_fallbackPublishesMatchFailed() {
        // Feign read timeout is 2s.
        LOCATION_SERVICE.stubFor(get(urlPathEqualTo(NEARBY)).willReturn(okJson("[]").withFixedDelay(4000)));

        send("ride-d2");

        awaitFailed("ride-d2");
        assertThat(deadLetters()).noneMatch(record -> record.value().contains("ride-d2"));
    }

    @Test
    void reserveAnswers500_fallbackPublishesMatchFailed() {
        stubNearby(threeDrivers());
        stubReserve(aResponse().withStatus(500));

        send("ride-d3");

        awaitFailed("ride-d3");
        assertThat(matchedFor("ride-d3")).isEmpty();
        assertThat(deadLetters()).noneMatch(record -> record.value().contains("ride-d3"));
    }

    // e) ----------------------------------------------------------------------------------------------------

    @Test
    void malformedMessage_landsOnTheDeadLetterTopicAndTheNextValidMessageIsStillProcessed() {
        stubNearby(threeDrivers());
        stubReserve(okJson("{\"driverId\":\"near\"}"));
        String garbage = "{ this is not json";

        rawSender().send(KafkaTopics.RIDE_REQUESTED, "bad-key", garbage).join();

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(deadLetters()).anyMatch(record -> garbage.equals(record.value())));

        send("ride-e");

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(matchedFor("ride-e")).hasSize(1));
    }

    // helpers -----------------------------------------------------------------------------------------------

    private void send(String rideId) {
        RideRequestedEvent event = RideRequestedEvent.builder()
                .rideId(rideId).riderId("rider-" + rideId)
                .pickupLatitude(12.97).pickupLongitude(77.59).pickupAddress("MG Road")
                .dropLatitude(13.02).dropLongitude(77.65).dropAddress("Airport")
                .attempt(1).build();
        kafkaTemplate.send(KafkaTopics.RIDE_REQUESTED, rideId, event).join();
    }

    /** Sends a payload as-is (no JSON serializer), to simulate a corrupt producer. */
    private KafkaTemplate<String, String> rawSender() {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.producerProps(broker));
        props.put("key.serializer", StringSerializer.class);
        props.put("value.serializer", StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private void awaitFailed(String rideId) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Rec<RideMatchFailedEvent>> failed = failedFor(rideId);
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0).key()).isEqualTo(rideId);
            assertThat(failed.get(0).value().getReason()).isEqualTo("NO_DRIVERS_AVAILABLE");
            assertThat(failed.get(0).value().getRiderId()).isEqualTo("rider-" + rideId);
            assertThat(failed.get(0).value().getFailedAt()).isNotNull();
        });
    }

    private List<Rec<RideMatchedEvent>> matchedFor(String rideId) {
        return kafka.read(KafkaTopics.RIDE_MATCHED, RideMatchedEvent.class).stream()
                .filter(r -> rideId.equals(r.value().getRideId())).toList();
    }

    private List<Rec<RideMatchFailedEvent>> failedFor(String rideId) {
        return kafka.read(KafkaTopics.RIDE_MATCH_FAILED, RideMatchFailedEvent.class).stream()
                .filter(r -> rideId.equals(r.value().getRideId())).toList();
    }

    private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> deadLetters() {
        return kafka.readRaw(KafkaConfig.RIDE_REQUESTED_DLT);
    }

    private static void stubNearby(String json) {
        LOCATION_SERVICE.stubFor(get(urlPathEqualTo(NEARBY)).willReturn(okJson(json)));
    }

    private static void stubReserve(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        LOCATION_SERVICE.stubFor(post(urlPathEqualTo(RESERVE)).willReturn(response));
    }

    /** Deliberately not sorted: matching-service must rank them itself. */
    private static String threeDrivers() {
        return """
                [
                  {"driverId":"far","latitude":12.99,"longitude":77.61,"distanceInKm":3.0},
                  {"driverId":"mid","latitude":12.98,"longitude":77.60,"distanceInKm":1.5},
                  {"driverId":"near","latitude":12.971,"longitude":77.591,"distanceInKm":0.4}
                ]
                """;
    }
}
