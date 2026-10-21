package com.ayshriv.rideservice.messaging;

import com.ayshriv.events.RideMatchFailedEvent;
import com.ayshriv.events.RideMatchedEvent;
import com.ayshriv.rideservice.service.RideService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RideEventListenerTest {

    @Mock
    private RideService rideService;
    @InjectMocks
    private RideEventListener listener;

    private final RideMatchedEvent matched = RideMatchedEvent.builder()
            .rideId("ride-1").riderId("rider-1").driverId("driver-7").build();
    private final RideMatchFailedEvent failed = RideMatchFailedEvent.builder()
            .rideId("ride-1").riderId("rider-1").reason("NO_DRIVERS_AVAILABLE").build();

    @Test
    void rideMatched_assignsDriver() {
        listener.onRideMatched(matched);

        verify(rideService).assignDriver("ride-1", "driver-7");
    }

    @Test
    void rideMatchFailed_marksNoDriverFound() {
        listener.onRideMatchFailed(failed);

        verify(rideService).markNoDriverFound("ride-1", "NO_DRIVERS_AVAILABLE");
    }

    @Test
    void rideMatched_retriesOptimisticLockConflictAndSucceedsOnSecondAttempt() {
        doThrow(new ObjectOptimisticLockingFailureException("Ride", "ride-1"))
                .doNothing()
                .when(rideService).assignDriver("ride-1", "driver-7");

        listener.onRideMatched(matched);

        verify(rideService, times(2)).assignDriver("ride-1", "driver-7");
    }

    @Test
    void rideMatchFailed_retriesOptimisticLockConflict() {
        doThrow(new ObjectOptimisticLockingFailureException("Ride", "ride-1"))
                .doNothing()
                .when(rideService).markNoDriverFound("ride-1", "NO_DRIVERS_AVAILABLE");

        listener.onRideMatchFailed(failed);

        verify(rideService, times(2)).markNoDriverFound("ride-1", "NO_DRIVERS_AVAILABLE");
    }

    @Test
    void rideMatched_givesUpAfterThreeConflictsSoTheRecordIsDeadLettered() {
        doThrow(new ObjectOptimisticLockingFailureException("Ride", "ride-1"))
                .when(rideService).assignDriver("ride-1", "driver-7");

        assertThatThrownBy(() -> listener.onRideMatched(matched))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(rideService, times(3)).assignDriver("ride-1", "driver-7");
    }

    @Test
    void rideMatched_doesNotRetryOtherFailures() {
        doThrow(new IllegalStateException("boom")).when(rideService).assignDriver("ride-1", "driver-7");

        assertThatThrownBy(() -> listener.onRideMatched(matched)).isInstanceOf(IllegalStateException.class);

        verify(rideService, times(1)).assignDriver("ride-1", "driver-7");
    }
}
