package com.ayshriv.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protects the wire contract between services: what one service serializes, the others must read.
 * The mapper is configured like Spring Boot's, except that unknown properties FAIL by default so the
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} on the events is what is really being tested.
 */
class EventSerializationTest {

    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    // ---- round trips -----------------------------------------------------------------------------------------

    @Test
    void rideRequestedEvent_roundTrips() throws Exception {
        RideRequestedEvent event = RideRequestedEvent.builder()
                .rideId("ride-1").riderId("rider-1")
                .pickupLatitude(12.9716).pickupLongitude(77.5946).pickupAddress("MG Road")
                .dropLatitude(13.1986).dropLongitude(77.7066).dropAddress("Airport")
                .requestedAt(T).attempt(2).build();

        assertThat(roundTrip(event, RideRequestedEvent.class)).isEqualTo(event);
    }

    @Test
    void rideMatchedEvent_roundTrips() throws Exception {
        RideMatchedEvent event = RideMatchedEvent.builder()
                .rideId("ride-1").riderId("rider-1").driverId("driver-7")
                .driverLatitude(12.9741).driverLongitude(77.5968).distanceToPickupKm(0.42)
                .matchedAt(T).build();

        assertThat(roundTrip(event, RideMatchedEvent.class)).isEqualTo(event);
    }

    @Test
    void rideMatchFailedEvent_roundTrips() throws Exception {
        RideMatchFailedEvent event = RideMatchFailedEvent.builder()
                .rideId("ride-1").riderId("rider-1").reason("NO_DRIVERS_AVAILABLE").failedAt(T).build();

        assertThat(roundTrip(event, RideMatchFailedEvent.class)).isEqualTo(event);
    }

    @Test
    void rideClosedEvent_roundTrips() throws Exception {
        RideClosedEvent event = RideClosedEvent.builder()
                .rideId("ride-1").riderId("rider-1").driverId("driver-7").finalStatus("COMPLETED").closedAt(T).build();

        assertThat(roundTrip(event, RideClosedEvent.class)).isEqualTo(event);
    }

    @Test
    void rideClosedEvent_roundTripsWithoutDriver() throws Exception {
        RideClosedEvent event = RideClosedEvent.builder()
                .rideId("ride-1").riderId("rider-1").finalStatus("NO_DRIVER_FOUND").closedAt(T).build();

        RideClosedEvent read = roundTrip(event, RideClosedEvent.class);

        assertThat(read.getDriverId()).isNull();
        assertThat(read).isEqualTo(event);
    }

    // ---- wire format -----------------------------------------------------------------------------------------

    @Test
    void instantsAreWrittenAsIso8601StringsNotNumbers() throws Exception {
        JsonNode requested = json(RideRequestedEvent.builder().rideId("r").riderId("u").requestedAt(T).build());
        JsonNode matched = json(RideMatchedEvent.builder().rideId("r").riderId("u").driverId("d").matchedAt(T).build());
        JsonNode failed = json(RideMatchFailedEvent.builder().rideId("r").riderId("u").failedAt(T).build());
        JsonNode closed = json(RideClosedEvent.builder().rideId("r").riderId("u").closedAt(T).build());

        assertThat(requested.get("requestedAt").asText()).isEqualTo("2026-01-01T10:00:00Z");
        assertThat(matched.get("matchedAt").asText()).isEqualTo("2026-01-01T10:00:00Z");
        assertThat(failed.get("failedAt").asText()).isEqualTo("2026-01-01T10:00:00Z");
        assertThat(closed.get("closedAt").asText()).isEqualTo("2026-01-01T10:00:00Z");
        assertThat(requested.get("requestedAt").isTextual()).isTrue();
    }

    @Test
    void fieldNamesAreTheDocumentedContract() throws Exception {
        assertThat(json(RideClosedEvent.builder().rideId("r").riderId("u").driverId("d")
                .finalStatus("CANCELLED").closedAt(T).build()).fieldNames())
                .toIterable().containsExactlyInAnyOrder("rideId", "riderId", "driverId", "finalStatus", "closedAt");
        assertThat(json(RideRequestedEvent.builder().build()).fieldNames())
                .toIterable().contains("rideId", "riderId", "pickupLatitude", "pickupLongitude", "dropLatitude",
                        "dropLongitude", "requestedAt", "attempt");
    }

    // ---- compatibility ---------------------------------------------------------------------------------------

    @Test
    void unknownPropertiesDoNotBreakDeserialization() throws Exception {
        String extra = ",\"addedByANewerVersion\":{\"nested\":true}";

        assertThat(mapper.readValue("{\"rideId\":\"r\",\"riderId\":\"u\"" + extra + "}", RideRequestedEvent.class)
                .getRideId()).isEqualTo("r");
        assertThat(mapper.readValue("{\"rideId\":\"r\",\"riderId\":\"u\",\"driverId\":\"d\"" + extra + "}",
                RideMatchedEvent.class).getDriverId()).isEqualTo("d");
        assertThat(mapper.readValue("{\"rideId\":\"r\",\"riderId\":\"u\",\"reason\":\"x\"" + extra + "}",
                RideMatchFailedEvent.class).getReason()).isEqualTo("x");
        assertThat(mapper.readValue("{\"rideId\":\"r\",\"riderId\":\"u\",\"finalStatus\":\"COMPLETED\"" + extra + "}",
                RideClosedEvent.class).getFinalStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void oldRideRequestedPayloadWithoutAttemptDefaultsToOne() throws Exception {
        String oldPayload = """
                {"rideId":"ride-1","riderId":"rider-1","pickupLatitude":12.97,"pickupLongitude":77.59,
                 "pickupAddress":"A","dropLatitude":13.02,"dropLongitude":77.65,"dropAddress":"B",
                 "requestedAt":"2026-01-01T10:00:00Z"}
                """;

        RideRequestedEvent event = mapper.readValue(oldPayload, RideRequestedEvent.class);

        assertThat(event.getAttempt()).isEqualTo(1);
        assertThat(event.getRequestedAt()).isEqualTo(T);
    }

    @Test
    void newlyBuiltAndNoArgsEventsDefaultToAttemptOne() {
        assertThat(RideRequestedEvent.builder().build().getAttempt()).isEqualTo(1);
        assertThat(new RideRequestedEvent().getAttempt()).isEqualTo(1);
    }

    @Test
    void explicitAttemptIsKept() throws Exception {
        RideRequestedEvent event = mapper.readValue("{\"rideId\":\"r\",\"riderId\":\"u\",\"attempt\":3}",
                RideRequestedEvent.class);

        assertThat(event.getAttempt()).isEqualTo(3);
    }

    @Test
    void topicNamesAreStable() {
        assertThat(KafkaTopics.RIDE_REQUESTED).isEqualTo("ride.requested");
        assertThat(KafkaTopics.RIDE_MATCHED).isEqualTo("ride.matched");
        assertThat(KafkaTopics.RIDE_MATCH_FAILED).isEqualTo("ride.match-failed");
        assertThat(KafkaTopics.RIDE_CLOSED).isEqualTo("ride.closed");
    }

    private <T> T roundTrip(T event, Class<T> type) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(event), type);
    }

    private JsonNode json(Object event) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(event));
    }
}
