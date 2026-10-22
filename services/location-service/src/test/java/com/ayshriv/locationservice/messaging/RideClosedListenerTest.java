package com.ayshriv.locationservice.messaging;

import com.ayshriv.events.RideClosedEvent;
import com.ayshriv.locationservice.service.LocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RideClosedListenerTest {

    @Mock
    private LocationService locationService;
    @InjectMocks
    private RideClosedListener listener;

    @Test
    void releasesTheDriverOfTheClosedRide() {
        listener.onRideClosed(RideClosedEvent.builder().rideId("ride-1").riderId("rider-1").driverId("d1")
                .finalStatus("COMPLETED").closedAt(Instant.now()).build());

        verify(locationService).releaseDriver("ride-1");
    }

    @Test
    void releasesEvenWhenRideClosedBeforeAnyDriverWasAssigned() {
        listener.onRideClosed(RideClosedEvent.builder().rideId("ride-2").riderId("rider-1")
                .finalStatus("CANCELLED").build());

        verify(locationService).releaseDriver("ride-2");
    }

    @Test
    void ignoresEventWithoutRideIdInsteadOfThrowing() {
        listener.onRideClosed(new RideClosedEvent());

        verifyNoInteractions(locationService);
    }

    @Test
    void redisFailuresPropagateSoTheErrorHandlerCanRetry() {
        doThrow(new IllegalStateException("redis down")).when(locationService).releaseDriver("ride-1");

        assertThatThrownBy(() -> listener.onRideClosed(RideClosedEvent.builder().rideId("ride-1").build()))
                .isInstanceOf(IllegalStateException.class);
    }
}
