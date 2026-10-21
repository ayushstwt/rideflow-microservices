package com.ayshriv.matchingservice.messaging;

import com.ayshriv.events.RideMatchFailedEvent;
import com.ayshriv.events.RideMatchedEvent;
import com.ayshriv.events.RideRequestedEvent;
import com.ayshriv.matchingservice.service.Match;
import com.ayshriv.matchingservice.service.MatchingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RideRequestedListenerTest {

    @Mock
    private MatchingService matchingService;
    @Mock
    private MatchEventPublisher publisher;
    @InjectMocks
    private RideRequestedListener listener;

    private final RideRequestedEvent request = RideRequestedEvent.builder()
            .rideId("ride-1").riderId("rider-1").pickupLatitude(12.97).pickupLongitude(77.59).build();

    @Test
    void publishesMatchedEventWhenDriverFound() {
        when(matchingService.findMatch(request)).thenReturn(Optional.of(new Match("driver-7", 12.98, 77.6, 1.2)));

        listener.onRideRequested(request);

        ArgumentCaptor<RideMatchedEvent> captor = ArgumentCaptor.forClass(RideMatchedEvent.class);
        verify(publisher).publishMatched(captor.capture());
        verify(publisher, never()).publishMatchFailed(any());
        RideMatchedEvent event = captor.getValue();
        assertThat(event.getRideId()).isEqualTo("ride-1");
        assertThat(event.getRiderId()).isEqualTo("rider-1");
        assertThat(event.getDriverId()).isEqualTo("driver-7");
        assertThat(event.getDriverLatitude()).isEqualTo(12.98);
        assertThat(event.getDriverLongitude()).isEqualTo(77.6);
        assertThat(event.getDistanceToPickupKm()).isEqualTo(1.2);
        assertThat(event.getMatchedAt()).isNotNull();
    }

    @Test
    void publishesMatchFailedEventWhenNoDriverFound() {
        when(matchingService.findMatch(request)).thenReturn(Optional.empty());

        listener.onRideRequested(request);

        ArgumentCaptor<RideMatchFailedEvent> captor = ArgumentCaptor.forClass(RideMatchFailedEvent.class);
        verify(publisher).publishMatchFailed(captor.capture());
        verify(publisher, never()).publishMatched(any());
        RideMatchFailedEvent event = captor.getValue();
        assertThat(event.getRideId()).isEqualTo("ride-1");
        assertThat(event.getRiderId()).isEqualTo("rider-1");
        assertThat(event.getReason()).isEqualTo("NO_DRIVERS_AVAILABLE");
        assertThat(event.getFailedAt()).isNotNull();
    }

    @Test
    void propagatesFailuresSoTheErrorHandlerCanRetryAndDeadLetter() {
        when(matchingService.findMatch(request)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> listener.onRideRequested(request)).isInstanceOf(IllegalStateException.class);

        verify(publisher, never()).publishMatched(any());
        verify(publisher, never()).publishMatchFailed(any());
    }
}
