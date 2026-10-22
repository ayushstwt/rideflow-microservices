package com.ayshriv.rideservice.service;

import com.ayshriv.events.RideClosedEvent;
import com.ayshriv.rideservice.config.RideMatchingProperties;
import com.ayshriv.rideservice.dto.RideMapper;
import com.ayshriv.rideservice.dto.RideRequest;
import com.ayshriv.rideservice.exception.ActiveRideExistsException;
import com.ayshriv.rideservice.exception.InvalidRideStateException;
import com.ayshriv.rideservice.messaging.RideClosedInternalEvent;
import com.ayshriv.rideservice.messaging.RideRequestedInternalEvent;
import com.ayshriv.rideservice.model.Ride;
import com.ayshriv.rideservice.model.RideStatus;
import com.ayshriv.rideservice.repository.RideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Idempotent creation, one active ride per rider, match retry policy and the ride.closed event. */
@ExtendWith(MockitoExtension.class)
class RideServiceReliabilityTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final BigDecimal FARE = new BigDecimal("150.00");

    @Mock
    private RideRepository rideRepository;
    @Mock
    private FareCalculator fareCalculator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RideService rideService;

    @BeforeEach
    void setUp() {
        // Defaults: max-attempts 3, retry-delay 15s.
        rideService = new RideService(rideRepository, new RideMapper(), fareCalculator,
                Clock.fixed(NOW, ZoneOffset.UTC), eventPublisher, new RideMatchingProperties(),
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    // ---- idempotent creation -------------------------------------------------------------------------------

    @Test
    void requestRide_sameKeyReturnsTheExistingRideWithoutCreatingOrCheckingActiveRides() {
        Ride existing = ride("r9", RideStatus.MATCHING, null);
        when(rideRepository.findByRiderIdAndIdempotencyKey("rider-1", "key-1")).thenReturn(Optional.of(existing));

        RideRequestResult result = rideService.requestRide(request(), "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.ride().getId()).isEqualTo("r9");
        verify(rideRepository, never()).saveAndFlush(any());
        verify(rideRepository, never()).findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void requestRide_differentKeyCreatesANewRideStoredWithThatKey() {
        stubSave();

        RideRequestResult result = rideService.requestRide(request(), "key-2");

        assertThat(result.created()).isTrue();
        ArgumentCaptor<Ride> saved = ArgumentCaptor.forClass(Ride.class);
        verify(rideRepository, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("key-2");
        assertThat(saved.getValue().getMatchAttempts()).isEqualTo(1);
    }

    @Test
    void requestRide_concurrentDuplicateResolvesToTheWinnersRide() {
        Ride winner = ride("r9", RideStatus.MATCHING, null);
        // Not there when checked, there after the unique constraint rejected our insert.
        when(rideRepository.findByRiderIdAndIdempotencyKey("rider-1", "key-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(fareCalculator.calculateFare(12.97, 77.59, 13.02, 77.65)).thenReturn(FARE);
        when(rideRepository.saveAndFlush(any(Ride.class))).thenThrow(new DataIntegrityViolationException("uk_rides_rider_idempotency_key"));

        RideRequestResult result = rideService.requestRide(request(), "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.ride().getId()).isEqualTo("r9");
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void requestRide_integrityViolationWithoutKeyIsNotSwallowed() {
        when(fareCalculator.calculateFare(12.97, 77.59, 13.02, 77.65)).thenReturn(FARE);
        when(rideRepository.saveAndFlush(any(Ride.class))).thenThrow(new DataIntegrityViolationException("other"));

        assertThatThrownBy(() -> rideService.requestRide(request(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(rideRepository, never()).findByRiderIdAndIdempotencyKey(any(), any());
    }

    @Test
    void requestRide_integrityViolationThatIsNotADuplicateKeyIsRethrown() {
        when(rideRepository.findByRiderIdAndIdempotencyKey("rider-1", "key-1")).thenReturn(Optional.empty());
        when(fareCalculator.calculateFare(12.97, 77.59, 13.02, 77.65)).thenReturn(FARE);
        when(rideRepository.saveAndFlush(any(Ride.class))).thenThrow(new DataIntegrityViolationException("other"));

        assertThatThrownBy(() -> rideService.requestRide(request(), "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- one active ride per rider -------------------------------------------------------------------------

    @Test
    void requestRide_rejectsRiderWhoAlreadyHasAnActiveRide() {
        when(rideRepository.findFirstByRiderIdAndStatusInOrderByCreatedAtDesc("rider-1", RideStatus.ACTIVE_STATUSES))
                .thenReturn(Optional.of(ride("r7", RideStatus.ACCEPTED, "d1")));

        assertThatThrownBy(() -> rideService.requestRide(request(), null))
                .isInstanceOfSatisfying(ActiveRideExistsException.class, e -> assertThat(e.getRideId()).isEqualTo("r7"));
        verify(rideRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void requestRide_isAllowedAgainWhenThePreviousRideIsFinished() {
        // The repository query only matches active statuses, so finished rides yield an empty result.
        stubSave();

        assertThat(rideService.requestRide(request(), null).created()).isTrue();
        assertThat(RideStatus.ACTIVE_STATUSES).containsExactlyInAnyOrder(RideStatus.REQUESTED, RideStatus.MATCHING,
                RideStatus.ACCEPTED, RideStatus.DRIVER_ARRIVING, RideStatus.RIDE_STARTED);
        assertThat(RideStatus.ACTIVE_STATUSES).doesNotContain(RideStatus.COMPLETED, RideStatus.CANCELLED,
                RideStatus.NO_DRIVER_FOUND);
    }

    // ---- retry policy --------------------------------------------------------------------------------------

    @Test
    void markNoDriverFound_withAttemptsLeftKeepsMatchingAndStoresTheReason() {
        Ride ride = matchingRide("r1", 1, NOW.minusSeconds(20));
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.markNoDriverFound("r1", "NO_DRIVERS_AVAILABLE");

        assertThat(ride.getStatus()).isEqualTo(RideStatus.MATCHING);
        assertThat(ride.getLastFailureReason()).isEqualTo("NO_DRIVERS_AVAILABLE");
        verify(rideRepository).saveAndFlush(ride);
        assertThat(closedEvents()).isEmpty();
    }

    @Test
    void markNoDriverFound_onTheSecondOfThreeAttemptsStillRetries() {
        Ride ride = matchingRide("r1", 2, NOW.minusSeconds(20));
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.markNoDriverFound("r1", "NO_DRIVERS_AVAILABLE");

        assertThat(ride.getStatus()).isEqualTo(RideStatus.MATCHING);
        assertThat(closedEvents()).isEmpty();
    }

    @Test
    void markNoDriverFound_withAttemptsExhaustedGivesUpAndPublishesClosedEvent() {
        Ride ride = matchingRide("r1", 3, NOW.minusSeconds(20));
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.markNoDriverFound("r1", "NO_DRIVERS_AVAILABLE");

        assertThat(ride.getStatus()).isEqualTo(RideStatus.NO_DRIVER_FOUND);
        assertThat(ride.getLastFailureReason()).isEqualTo("NO_DRIVERS_AVAILABLE");
        verify(rideRepository).saveAndFlush(ride);
        assertThat(closedEvents()).singleElement().satisfies(event -> {
            assertThat(event.getRideId()).isEqualTo("r1");
            assertThat(event.getRiderId()).isEqualTo("rider-1");
            assertThat(event.getDriverId()).isNull();
            assertThat(event.getFinalStatus()).isEqualTo("NO_DRIVER_FOUND");
            assertThat(event.getClosedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void markNoDriverFound_truncatesAnOverlongReasonToTheColumnSize() {
        Ride ride = matchingRide("r1", 1, NOW);
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.markNoDriverFound("r1", "x".repeat(300));

        assertThat(ride.getLastFailureReason()).hasSize(255);
    }

    // ---- scheduler step ------------------------------------------------------------------------------------

    @Test
    void retryStuckRide_countsANewAttemptAndRepublishesRideRequested() {
        Ride ride = matchingRide("r1", 1, NOW.minusSeconds(16));
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.retryStuckRide("r1");

        assertThat(ride.getMatchAttempts()).isEqualTo(2);
        assertThat(ride.getLastMatchAttemptAt()).isEqualTo(NOW);
        assertThat(ride.getStatus()).isEqualTo(RideStatus.MATCHING);
        verify(rideRepository).saveAndFlush(ride);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(RideRequestedInternalEvent.class,
                event -> assertThat(event.ride().getMatchAttempts()).isEqualTo(2));
    }

    @Test
    void retryStuckRide_givesUpWhenAttemptsAreExhaustedAndPublishesClosedEvent() {
        Ride ride = matchingRide("r1", 3, NOW.minusSeconds(16));
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.retryStuckRide("r1");

        assertThat(ride.getStatus()).isEqualTo(RideStatus.NO_DRIVER_FOUND);
        assertThat(ride.getLastFailureReason()).isEqualTo("NO_MATCH_RESULT");
        assertThat(ride.getMatchAttempts()).isEqualTo(3);
        verify(rideRepository).saveAndFlush(ride);
        assertThat(closedEvents()).singleElement()
                .satisfies(event -> assertThat(event.getFinalStatus()).isEqualTo("NO_DRIVER_FOUND"));
        verify(eventPublisher, never()).publishEvent(any(RideRequestedInternalEvent.class));
    }

    @Test
    void retryStuckRide_keepsTheFailureReasonThatAlreadyExists() {
        Ride ride = matchingRide("r1", 3, NOW.minusSeconds(16));
        ride.setLastFailureReason("NO_DRIVERS_AVAILABLE");
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.retryStuckRide("r1");

        assertThat(ride.getLastFailureReason()).isEqualTo("NO_DRIVERS_AVAILABLE");
    }

    @Test
    void retryStuckRide_skipsARideThatWasRetriedRecently() {
        Ride ride = matchingRide("r1", 1, NOW.minusSeconds(5));
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.retryStuckRide("r1");

        assertThat(ride.getMatchAttempts()).isEqualTo(1);
        verify(rideRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void retryStuckRide_ignoresARideThatIsNoLongerMatching() {
        Ride ride = ride("r1", RideStatus.ACCEPTED, "d1");
        ride.setLastMatchAttemptAt(NOW.minusSeconds(60));
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));

        rideService.retryStuckRide("r1");

        verify(rideRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void retryStuckRide_lostOptimisticLockRaceSurfacesToTheCallerAndPublishesNothingElse() {
        Ride ride = matchingRide("r1", 1, NOW.minusSeconds(16));
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride));
        when(rideRepository.saveAndFlush(ride)).thenThrow(new ObjectOptimisticLockingFailureException(Ride.class, "r1"));

        assertThatThrownBy(() -> rideService.retryStuckRide("r1"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // ---- ride.closed ---------------------------------------------------------------------------------------

    @Test
    void cancelRide_publishesExactlyOneClosedEventWithTheDriver() {
        Ride ride = ride("r1", RideStatus.ACCEPTED, "d1");
        stubFindAndSave(ride);

        rideService.cancelRide("r1");

        assertThat(closedEvents()).singleElement().satisfies(event -> {
            assertThat(event.getRideId()).isEqualTo("r1");
            assertThat(event.getDriverId()).isEqualTo("d1");
            assertThat(event.getFinalStatus()).isEqualTo("CANCELLED");
            assertThat(event.getClosedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void cancelRide_beforeAnyDriverIsAssignedHasNullDriver() {
        stubFindAndSave(ride("r1", RideStatus.MATCHING, null));

        rideService.cancelRide("r1");

        assertThat(closedEvents()).singleElement().satisfies(event -> {
            assertThat(event.getDriverId()).isNull();
            assertThat(event.getFinalStatus()).isEqualTo("CANCELLED");
        });
    }

    @Test
    void completeRide_publishesExactlyOneClosedEvent() {
        stubFindAndSave(ride("r1", RideStatus.RIDE_STARTED, "d1"));

        rideService.completeRide("r1");

        assertThat(closedEvents()).singleElement().satisfies(event -> {
            assertThat(event.getDriverId()).isEqualTo("d1");
            assertThat(event.getFinalStatus()).isEqualTo("COMPLETED");
        });
    }

    @Test
    void nonTerminalTransitionsNeverPublishAClosedEvent() {
        // requestRide: REQUESTED -> MATCHING
        stubSave();
        rideService.requestRide(request(), null);

        // assignDriver: MATCHING -> ACCEPTED
        Ride matching = ride("r2", RideStatus.MATCHING, null);
        when(rideRepository.findById("r2")).thenReturn(Optional.of(matching));
        rideService.assignDriver("r2", "d1");

        // startRide: ACCEPTED -> RIDE_STARTED
        Ride accepted = ride("r3", RideStatus.ACCEPTED, "d1");
        stubFindAndSave(accepted);
        rideService.startRide("r3");

        // retry with attempts left
        Ride stuck = matchingRide("r4", 1, NOW.minusSeconds(30));
        when(rideRepository.findById("r4")).thenReturn(Optional.of(stuck));
        rideService.retryStuckRide("r4");

        assertThat(closedEvents()).isEmpty();
    }

    @Test
    void rejectedTransitionNeverPublishesAClosedEvent() {
        when(rideRepository.findById("r1")).thenReturn(Optional.of(ride("r1", RideStatus.COMPLETED, "d1")));

        assertThatThrownBy(() -> rideService.cancelRide("r1")).isInstanceOf(InvalidRideStateException.class);

        assertThat(closedEvents()).isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private List<RideClosedEvent> closedEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(RideClosedInternalEvent.class::isInstance)
                .map(event -> ((RideClosedInternalEvent) event).event())
                .toList();
    }

    private void stubSave() {
        when(fareCalculator.calculateFare(12.97, 77.59, 13.02, 77.65)).thenReturn(FARE);
        when(rideRepository.saveAndFlush(any(Ride.class))).thenAnswer(invocation -> {
            Ride ride = invocation.getArgument(0);
            if (ride.getId() == null) {
                ride.setId("new-ride");
            }
            return ride;
        });
    }

    private void stubFindAndSave(Ride ride) {
        when(rideRepository.findById(ride.getId())).thenReturn(Optional.of(ride));
        when(rideRepository.saveAndFlush(ride)).thenReturn(ride);
    }

    private static RideRequest request() {
        return RideRequest.builder()
                .riderId("rider-1")
                .pickupLatitude(12.97).pickupLongitude(77.59).pickupAddress("A")
                .dropLatitude(13.02).dropLongitude(77.65).dropAddress("B")
                .build();
    }

    private static Ride matchingRide(String id, int attempts, Instant lastAttemptAt) {
        Ride ride = ride(id, RideStatus.MATCHING, null);
        ride.setMatchAttempts(attempts);
        ride.setLastMatchAttemptAt(lastAttemptAt);
        return ride;
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
