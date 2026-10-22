package com.ayshriv.rideservice.messaging;

import com.ayshriv.events.KafkaTopics;
import com.ayshriv.events.RideClosedEvent;
import com.ayshriv.events.RideRequestedEvent;
import com.ayshriv.rideservice.model.Ride;
import com.ayshriv.rideservice.model.RideStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RideEventPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void publishRideRequested_usesTopicAndRideIdAsKeyAndMapsPayload() {
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(new CompletableFuture<SendResult<String, Object>>());
        Instant before = Instant.now();

        new RideEventPublisher(kafkaTemplate).publishRideRequested(ride());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.RIDE_REQUESTED), eq("ride-1"), payload.capture());
        RideRequestedEvent event = (RideRequestedEvent) payload.getValue();
        assertThat(event.getRideId()).isEqualTo("ride-1");
        assertThat(event.getRiderId()).isEqualTo("rider-1");
        assertThat(event.getPickupLatitude()).isEqualTo(12.97);
        assertThat(event.getPickupLongitude()).isEqualTo(77.59);
        assertThat(event.getPickupAddress()).isEqualTo("A");
        assertThat(event.getDropLatitude()).isEqualTo(13.02);
        assertThat(event.getDropLongitude()).isEqualTo(77.65);
        assertThat(event.getDropAddress()).isEqualTo("B");
        assertThat(event.getRequestedAt()).isBetween(before, Instant.now());
        assertThat(event.getAttempt()).isEqualTo(1);
    }

    @Test
    void publishRideRequested_carriesTheCurrentMatchAttempt() {
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(new CompletableFuture<SendResult<String, Object>>());
        Ride ride = ride();
        ride.setMatchAttempts(3);

        new RideEventPublisher(kafkaTemplate).publishRideRequested(ride);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.RIDE_REQUESTED), eq("ride-1"), payload.capture());
        assertThat(((RideRequestedEvent) payload.getValue()).getAttempt()).isEqualTo(3);
    }

    @Test
    void publishRideClosed_usesClosedTopicAndRideIdAsKey() {
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(new CompletableFuture<SendResult<String, Object>>());
        RideClosedEvent closed = RideClosedEvent.builder().rideId("ride-1").riderId("rider-1").driverId("d1")
                .finalStatus("COMPLETED").closedAt(Instant.now()).build();

        new RideEventPublisher(kafkaTemplate).publishRideClosed(closed);

        verify(kafkaTemplate).send(KafkaTopics.RIDE_CLOSED, "ride-1", closed);
    }

    @Test
    void publishRideClosed_failedSendIsHandledByCallbackWithoutThrowing() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(String.class), any(String.class), any())).thenReturn(future);
        RideEventPublisher publisher = new RideEventPublisher(kafkaTemplate);

        assertThatCode(() -> {
            publisher.publishRideClosed(RideClosedEvent.builder().rideId("ride-1").build());
            future.completeExceptionally(new IllegalStateException("broker down"));
        }).doesNotThrowAnyException();
    }

    @Test
    void publishRideRequested_failedSendIsHandledByCallbackWithoutThrowing() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(String.class), any(String.class), any())).thenReturn(future);

        RideEventPublisher publisher = new RideEventPublisher(kafkaTemplate);

        assertThatCode(() -> {
            publisher.publishRideRequested(ride());
            future.completeExceptionally(new IllegalStateException("broker down"));
        }).doesNotThrowAnyException();
    }

    private static Ride ride() {
        return Ride.builder()
                .id("ride-1")
                .riderId("rider-1")
                .pickupLatitude(12.97).pickupLongitude(77.59).pickupAddress("A")
                .dropLatitude(13.02).dropLongitude(77.65).dropAddress("B")
                .status(RideStatus.MATCHING)
                .build();
    }
}
