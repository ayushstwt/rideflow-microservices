package com.ayshriv.rideservice;

import com.ayshriv.events.KafkaTopics;
import com.ayshriv.events.RideClosedEvent;
import com.ayshriv.events.RideMatchFailedEvent;
import com.ayshriv.events.RideMatchedEvent;
import com.ayshriv.events.RideRequestedEvent;
import com.ayshriv.rideservice.messaging.RideRequestedInternalEvent;
import com.ayshriv.rideservice.model.Ride;
import com.ayshriv.rideservice.model.RideStatus;
import com.ayshriv.rideservice.repository.RideRepository;
import com.ayshriv.rideservice.support.TestKafkaReader;
import com.ayshriv.rideservice.support.TestKafkaReader.Rec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole ride-service in one JVM: real Spring context, H2, an embedded Kafka broker and the real scheduler.
 * Retries are made fast (2s delay, 1s scheduler interval, 3 attempts). Each test uses its own rider and
 * filters Kafka records by rideId, so tests do not see each other's traffic.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:h2:mem:ride_flow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "ride.matching.max-attempts=3",
        "ride.matching.retry-delay-seconds=2",
        "ride.matching.scheduler-interval-seconds=1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, topics = {
        KafkaTopics.RIDE_REQUESTED, KafkaTopics.RIDE_MATCHED, KafkaTopics.RIDE_MATCH_FAILED, KafkaTopics.RIDE_CLOSED})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RideFlowIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final String BODY = """
            {
              "riderId": "%s",
              "pickupLatitude": 12.97, "pickupLongitude": 77.59, "pickupAddress": "MG Road",
              "dropLatitude": 13.02, "dropLongitude": 77.65, "dropAddress": "Airport"
            }
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RideRepository rideRepository;
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private ObjectMapper objectMapper;

    private TestKafkaReader kafka;

    @BeforeEach
    void setUp() {
        kafka = new TestKafkaReader(broker.getBrokersAsString());
    }

    // a) ----------------------------------------------------------------------------------------------------

    @Test
    void requestRide_publishesExactlyOneRideRequestedKeyedByRideIdWithAttempt1() throws Exception {
        String rideId = requestRide("rider-a", null, 201);
        cancel(rideId); // stops the scheduler from retrying while we look

        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Rec<RideRequestedEvent>> records = requestedFor(rideId);
            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo(rideId);
            assertThat(records.get(0).value().getAttempt()).isEqualTo(1);
            assertThat(records.get(0).value().getRiderId()).isEqualTo("rider-a");
        });
        Ride ride = rideRepository.findById(rideId).orElseThrow();
        assertThat(ride.getMatchAttempts()).isEqualTo(1);
        assertThat(ride.getLastMatchAttemptAt()).isNotNull();
    }

    // b) ----------------------------------------------------------------------------------------------------

    @Test
    void rideMatched_acceptsTheRideAndADuplicateChangesNothing() throws Exception {
        String rideId = requestRide("rider-b", null, 201);

        send(KafkaTopics.RIDE_MATCHED, rideId, matched(rideId, "rider-b", "driver-1"));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Ride ride = rideRepository.findById(rideId).orElseThrow();
            assertThat(ride.getStatus()).isEqualTo(RideStatus.ACCEPTED);
            assertThat(ride.getDriverId()).isEqualTo("driver-1");
        });
        Long version = rideRepository.findById(rideId).orElseThrow().getVersion();

        send(KafkaTopics.RIDE_MATCHED, rideId, matched(rideId, "rider-b", "driver-1"));
        send(KafkaTopics.RIDE_MATCHED, rideId, matched(rideId, "rider-b", "driver-2"));

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            Ride ride = rideRepository.findById(rideId).orElseThrow();
            assertThat(ride.getStatus()).isEqualTo(RideStatus.ACCEPTED);
            assertThat(ride.getDriverId()).isEqualTo("driver-1");
            assertThat(ride.getVersion()).isEqualTo(version);
        });
    }

    // c) ----------------------------------------------------------------------------------------------------

    @Test
    void matchFailure_keepsMatchingAndSchedulerRetriesThenGivesUpAndClosesTheRide() throws Exception {
        String rideId = requestRide("rider-c", null, 201);

        // Attempt 1 fails, attempts are left: stays MATCHING and the scheduler republishes with attempt 2.
        send(KafkaTopics.RIDE_MATCH_FAILED, rideId, failed(rideId, "rider-c"));
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Ride ride = rideRepository.findById(rideId).orElseThrow();
            assertThat(ride.getStatus()).isEqualTo(RideStatus.MATCHING);
            assertThat(ride.getLastFailureReason()).isEqualTo("NO_DRIVERS_AVAILABLE");
        });
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(requestedFor(rideId)).extracting(r -> r.value().getAttempt()).contains(1, 2));

        // Attempt 2 fails: still MATCHING, scheduler republishes attempt 3.
        send(KafkaTopics.RIDE_MATCH_FAILED, rideId, failed(rideId, "rider-c"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(requestedFor(rideId)).extracting(r -> r.value().getAttempt()).contains(1, 2, 3));
        assertThat(requestedFor(rideId)).extracting(r -> r.value().getAttempt()).doesNotHaveDuplicates();

        // Attempt 3 (the last) fails: NO_DRIVER_FOUND and the driver release event.
        send(KafkaTopics.RIDE_MATCH_FAILED, rideId, failed(rideId, "rider-c"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(rideRepository.findById(rideId).orElseThrow().getStatus()).isEqualTo(RideStatus.NO_DRIVER_FOUND));
        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Rec<RideClosedEvent>> closed = closedFor(rideId);
            assertThat(closed).hasSize(1);
            assertThat(closed.get(0).key()).isEqualTo(rideId);
            assertThat(closed.get(0).value().getFinalStatus()).isEqualTo("NO_DRIVER_FOUND");
            assertThat(closed.get(0).value().getDriverId()).isNull();
            assertThat(closed.get(0).value().getRiderId()).isEqualTo("rider-c");
        });
        // No attempt beyond max-attempts was ever published.
        assertThat(requestedFor(rideId)).extracting(r -> r.value().getAttempt()).containsOnly(1, 2, 3);
    }

    @Test
    void lostMatchResult_isRecoveredByTheSchedulerAndEventuallyGivenUp() throws Exception {
        // Nobody answers ride.requested at all (message "lost"): the scheduler re-drives it on its own.
        String rideId = requestRide("rider-c2", null, 201);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(rideRepository.findById(rideId).orElseThrow().getStatus()).isEqualTo(RideStatus.NO_DRIVER_FOUND));

        assertThat(requestedFor(rideId)).extracting(r -> r.value().getAttempt()).containsExactlyInAnyOrder(1, 2, 3);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(closedFor(rideId)).hasSize(1));
    }

    // d) ----------------------------------------------------------------------------------------------------

    @Test
    void cancellingAnAcceptedRide_publishesRideClosedWithTheDriver() throws Exception {
        String rideId = requestRide("rider-d", null, 201);
        send(KafkaTopics.RIDE_MATCHED, rideId, matched(rideId, "rider-d", "driver-9"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(rideRepository.findById(rideId).orElseThrow().getStatus()).isEqualTo(RideStatus.ACCEPTED));

        cancel(rideId);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Rec<RideClosedEvent>> closed = closedFor(rideId);
            assertThat(closed).hasSize(1);
            assertThat(closed.get(0).key()).isEqualTo(rideId);
            assertThat(closed.get(0).value().getDriverId()).isEqualTo("driver-9");
            assertThat(closed.get(0).value().getFinalStatus()).isEqualTo("CANCELLED");
        });
    }

    // e) ----------------------------------------------------------------------------------------------------

    @Test
    void sameIdempotencyKeyTwice_returnsTheSameRideAndPublishesOnlyOneRequest() throws Exception {
        String first = requestRide("rider-e", "key-e", 201);
        String second = requestRide("rider-e", "key-e", 200);
        cancel(first); // stops the scheduler from legitimately republishing while we count

        assertThat(second).isEqualTo(first);
        assertThat(rideRepository.findByRiderIdOrderByCreatedAtDesc("rider-e")).hasSize(1);
        await().during(Duration.ofMillis(500)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(requestedFor(first)).hasSize(1));
    }

    // f) ----------------------------------------------------------------------------------------------------

    @Test
    void failingTransaction_publishesNothingAndLeavesNoRide() throws Exception {
        mockMvc.perform(post("/api/v1/rides/request").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted(FailBeforeCommit.RIDER)))
                .andExpect(status().isInternalServerError());

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(rideRepository.findByRiderIdOrderByCreatedAtDesc(FailBeforeCommit.RIDER)).isEmpty();
            assertThat(kafka.read(KafkaTopics.RIDE_REQUESTED, RideRequestedEvent.class))
                    .noneMatch(r -> FailBeforeCommit.RIDER.equals(r.value().getRiderId()));
        });
    }

    /** Makes the commit of one specific rider's request fail, after the ride was written but before it commits. */
    @TestConfiguration
    static class FailBeforeCommit {
        static final String RIDER = "rider-f-fails";

        @Bean
        FailBeforeCommitListener failBeforeCommitListener() {
            return new FailBeforeCommitListener();
        }
    }

    static class FailBeforeCommitListener {
        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        void onRideRequested(RideRequestedInternalEvent event) {
            if (FailBeforeCommit.RIDER.equals(event.ride().getRiderId())) {
                throw new IllegalStateException("simulated failure right before commit");
            }
        }
    }

    // helpers -----------------------------------------------------------------------------------------------

    private String requestRide(String riderId, String idempotencyKey, int expectedStatus) throws Exception {
        var request = post("/api/v1/rides/request").contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(riderId));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void cancel(String rideId) throws Exception {
        mockMvc.perform(put("/api/v1/rides/{id}/cancel", rideId)).andExpect(status().isOk());
    }

    private void send(String topic, String rideId, Object event) {
        kafkaTemplate.send(topic, rideId, event).join();
    }

    private List<Rec<RideRequestedEvent>> requestedFor(String rideId) {
        return kafka.read(KafkaTopics.RIDE_REQUESTED, RideRequestedEvent.class).stream()
                .filter(r -> rideId.equals(r.value().getRideId())).toList();
    }

    private List<Rec<RideClosedEvent>> closedFor(String rideId) {
        return kafka.read(KafkaTopics.RIDE_CLOSED, RideClosedEvent.class).stream()
                .filter(r -> rideId.equals(r.value().getRideId())).toList();
    }

    private static RideMatchedEvent matched(String rideId, String riderId, String driverId) {
        return RideMatchedEvent.builder().rideId(rideId).riderId(riderId).driverId(driverId)
                .driverLatitude(12.98).driverLongitude(77.6).distanceToPickupKm(1.0).build();
    }

    private static RideMatchFailedEvent failed(String rideId, String riderId) {
        return RideMatchFailedEvent.builder().rideId(rideId).riderId(riderId).reason("NO_DRIVERS_AVAILABLE").build();
    }
}
