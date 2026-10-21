package com.rideshare.rideservice.service;

import com.rideshare.rideservice.dto.RideMapper;
import com.rideshare.rideservice.dto.RideRequest;
import com.rideshare.rideservice.dto.RideResponse;
import com.rideshare.rideservice.exception.InvalidRideStateException;
import com.rideshare.rideservice.exception.RideNotFoundException;
import com.rideshare.rideservice.model.Ride;
import com.rideshare.rideservice.model.RideStatus;
import com.rideshare.rideservice.repository.RideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RideServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final BigDecimal FARE = new BigDecimal("150.00");

    @Mock
    private RideRepository rideRepository;
    @Mock
    private FareCalculator fareCalculator;

    private RideService rideService;

    @BeforeEach
    void setUp() {
        rideService = new RideService(rideRepository, new RideMapper(), fareCalculator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void requestRide_savesRequestedThenMovesToMatchingInOneCall() {
        RideRequest request = RideRequest.builder()
                .riderId("rider-1")
                .pickupLatitude(12.97).pickupLongitude(77.59).pickupAddress("A")
                .dropLatitude(13.02).dropLongitude(77.65).dropAddress("B")
                .build();
        when(fareCalculator.calculateFare(12.97, 77.59, 13.02, 77.65)).thenReturn(FARE);
        when(rideRepository.saveAndFlush(any(Ride.class))).thenAnswer(invocation -> {
            Ride ride = invocation.getArgument(0);
            if (ride.getId() == null) {
                ride.setId("ride-1");
            }
            return ride;
        });

        RideResponse response = rideService.requestRide(request);

        assertThat(response.getId()).isEqualTo("ride-1");
        assertThat(response.getRiderId()).isEqualTo("rider-1");
        assertThat(response.getStatus()).isEqualTo(RideStatus.MATCHING);
        assertThat(response.getEstimatedFare()).isEqualByComparingTo(FARE);
        verify(rideRepository, times(2)).saveAndFlush(any(Ride.class));
    }

    @Test
    void getRideById_throwsWhenMissing() {
        when(rideRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rideService.getRideById("missing"))
                .isInstanceOf(RideNotFoundException.class);
    }

    @Test
    void getRidesByRider_mapsRepositoryResults() {
        when(rideRepository.findByRiderIdOrderByCreatedAtDesc("rider-1"))
                .thenReturn(List.of(ride("r1", RideStatus.COMPLETED, "d1"), ride("r2", RideStatus.MATCHING, null)));

        List<RideResponse> rides = rideService.getRidesByRider("rider-1");

        assertThat(rides).extracting(RideResponse::getId).containsExactly("r1", "r2");
    }

    @Test
    void startRide_setsStartedAtWhenDriverAssigned() {
        Ride ride = ride("r1", RideStatus.ACCEPTED, "d1");
        stubFindAndSave(ride);

        RideResponse response = rideService.startRide("r1");

        assertThat(response.getStatus()).isEqualTo(RideStatus.RIDE_STARTED);
        assertThat(response.getStartedAt()).isEqualTo(NOW);
    }

    @Test
    void startRide_requiresAssignedDriver() {
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride("r1", RideStatus.MATCHING, null)));

        assertThatThrownBy(() -> rideService.startRide("r1"))
                .isInstanceOf(InvalidRideStateException.class)
                .hasMessageContaining("driver");
        verify(rideRepository, never()).saveAndFlush(any());
    }

    @Test
    void completeRide_setsCompletedAtAndActualFareFromEstimate() {
        Ride ride = ride("r1", RideStatus.RIDE_STARTED, "d1");
        stubFindAndSave(ride);

        RideResponse response = rideService.completeRide("r1");

        assertThat(response.getStatus()).isEqualTo(RideStatus.COMPLETED);
        assertThat(response.getCompletedAt()).isEqualTo(NOW);
        assertThat(response.getActualFare()).isEqualByComparingTo(FARE);
    }

    @Test
    void completeRide_rejectsRideThatNeverStarted() {
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride("r1", RideStatus.ACCEPTED, "d1")));

        assertThatThrownBy(() -> rideService.completeRide("r1"))
                .isInstanceOf(InvalidRideStateException.class);
        verify(rideRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelRide_movesToCancelled() {
        stubFindAndSave(ride("r1", RideStatus.MATCHING, null));

        assertThat(rideService.cancelRide("r1").getStatus()).isEqualTo(RideStatus.CANCELLED);
    }

    @Test
    void cancelRide_rejectsTerminalRide() {
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride("r1", RideStatus.COMPLETED, "d1")));

        assertThatThrownBy(() -> rideService.cancelRide("r1"))
                .isInstanceOf(InvalidRideStateException.class);
    }

    @Test
    void assignDriver_acceptsRideInMatching() {
        Ride ride = ride("r1", RideStatus.MATCHING, null);
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.assignDriver("r1", "d9");

        assertThat(ride.getDriverId()).isEqualTo("d9");
        assertThat(ride.getStatus()).isEqualTo(RideStatus.ACCEPTED);
        verify(rideRepository).saveAndFlush(ride);
    }

    @Test
    void assignDriver_ignoresRideNotInMatching() {
        Ride ride = ride("r1", RideStatus.ACCEPTED, "d1");
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.assignDriver("r1", "d9");

        assertThat(ride.getDriverId()).isEqualTo("d1");
        assertThat(ride.getStatus()).isEqualTo(RideStatus.ACCEPTED);
        verify(rideRepository, never()).saveAndFlush(any());
    }

    @Test
    void assignDriver_ignoresUnknownRide() {
        when(rideRepository.findById("missing")).thenReturn(Optional.empty());

        rideService.assignDriver("missing", "d9");

        verify(rideRepository, never()).saveAndFlush(any());
    }

    private void stubFindAndSave(Ride ride) {
        when(rideRepository.findById(ride.getId())).thenReturn(Optional.of(ride));
        when(rideRepository.saveAndFlush(ride)).thenReturn(ride);
    }

    private static Ride ride(String id, RideStatus status, String driverId) {
        return Ride.builder()
                .id(id)
                .riderId("rider-1")
                .driverId(driverId)
                .pickupLatitude(12.97).pickupLongitude(77.59).pickupAddress("A")
                .dropLatitude(13.02).dropLongitude(77.65).dropAddress("B")
                .status(status)
                .estimatedFare(FARE)
                .build();
    }
}
