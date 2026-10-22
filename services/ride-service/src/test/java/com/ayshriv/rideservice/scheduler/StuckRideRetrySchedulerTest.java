package com.ayshriv.rideservice.scheduler;

import com.ayshriv.rideservice.config.RideMatchingProperties;
import com.ayshriv.rideservice.dto.RideMapper;
import com.ayshriv.rideservice.messaging.RideClosedInternalEvent;
import com.ayshriv.rideservice.messaging.RideRequestedInternalEvent;
import com.ayshriv.rideservice.model.Ride;
import com.ayshriv.rideservice.model.RideStatus;
import com.ayshriv.rideservice.repository.RideRepository;
import com.ayshriv.rideservice.service.FareCalculator;
import com.ayshriv.rideservice.service.RideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StuckRideRetrySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RideMatchingProperties properties = new RideMatchingProperties();

    @Mock
    private RideRepository rideRepository;
    @Mock
    private RideService rideService;
    @Mock
    private FareCalculator fareCalculator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private StuckRideRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new StuckRideRetryScheduler(rideRepository, rideService, properties, clock);
    }

    @Test
    void asksOnlyForMatchingRidesOlderThanTheRetryDelayInBatchesOf50() {
        when(rideRepository.findByStatusAndLastMatchAttemptAtBefore(any(), any(), any())).thenReturn(List.of());

        scheduler.retryStuckRides();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(rideRepository).findByStatusAndLastMatchAttemptAtBefore(
                eq(RideStatus.MATCHING), eq(NOW.minusSeconds(15)), page.capture());
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(50);
        assertThat(page.getValue().getSort().getOrderFor("lastMatchAttemptAt")).isNotNull();
        verify(rideService, never()).retryStuckRide(any());
    }

    @Test
    void retriesEveryRideReturnedByTheQuery() {
        when(rideRepository.findByStatusAndLastMatchAttemptAtBefore(any(), any(), any()))
                .thenReturn(List.of(ride("r1"), ride("r2"), ride("r3")));

        scheduler.retryStuckRides();

        verify(rideService).retryStuckRide("r1");
        verify(rideService).retryStuckRide("r2");
        verify(rideService).retryStuckRide("r3");
    }

    @Test
    void lostOptimisticLockRaceIsIgnoredAndTheRestOfTheBatchStillRuns() {
        when(rideRepository.findByStatusAndLastMatchAttemptAtBefore(any(), any(), any()))
                .thenReturn(List.of(ride("r1"), ride("r2")));
        doThrow(new ObjectOptimisticLockingFailureException(Ride.class, "r1")).when(rideService).retryStuckRide("r1");

        assertThatCode(() -> scheduler.retryStuckRides()).doesNotThrowAnyException();

        verify(rideService).retryStuckRide("r2");
    }

    @Test
    void anyOtherFailureOfOneRideDoesNotStopTheBatchEither() {
        when(rideRepository.findByStatusAndLastMatchAttemptAtBefore(any(), any(), any()))
                .thenReturn(List.of(ride("r1"), ride("r2")));
        doThrow(new IllegalStateException("boom")).when(rideService).retryStuckRide("r1");

        assertThatCode(() -> scheduler.retryStuckRides()).doesNotThrowAnyException();

        verify(rideService).retryStuckRide("r2");
    }

    @Test
    void withTheRealServiceAStuckRideIsRepublishedWithTheIncrementedAttempt() {
        Ride stuck = stuckRide("r1", 1);
        wireRealService(stuck);

        scheduler.retryStuckRides();

        assertThat(stuck.getMatchAttempts()).isEqualTo(2);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(RideRequestedInternalEvent.class,
                event -> assertThat(event.ride().getMatchAttempts()).isEqualTo(2));
    }

    @Test
    void withTheRealServiceARideThatUsedAllAttemptsIsClosedAsNoDriverFound() {
        Ride exhausted = stuckRide("r1", properties.getMaxAttempts());
        wireRealService(exhausted);

        scheduler.retryStuckRides();

        assertThat(exhausted.getStatus()).isEqualTo(RideStatus.NO_DRIVER_FOUND);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(RideClosedInternalEvent.class,
                event -> assertThat(event.event().getFinalStatus()).isEqualTo("NO_DRIVER_FOUND"));
    }

    @Test
    void withTheRealServiceALostRaceInOneRideDoesNotAffectTheOthers() {
        Ride first = stuckRide("r1", 1);
        Ride second = stuckRide("r2", 1);
        RideService real = realService();
        scheduler = new StuckRideRetryScheduler(rideRepository, real, properties, clock);
        when(rideRepository.findByStatusAndLastMatchAttemptAtBefore(any(), any(), any()))
                .thenReturn(List.of(first, second));
        when(rideRepository.findById("r1")).thenReturn(Optional.of(first));
        when(rideRepository.findById("r2")).thenReturn(Optional.of(second));
        when(rideRepository.saveAndFlush(first)).thenThrow(new ObjectOptimisticLockingFailureException(Ride.class, "r1"));
        when(rideRepository.saveAndFlush(second)).thenReturn(second);

        assertThatCode(() -> scheduler.retryStuckRides()).doesNotThrowAnyException();

        assertThat(second.getMatchAttempts()).isEqualTo(2);
        verify(eventPublisher).publishEvent(any(RideRequestedInternalEvent.class));
    }

    @Test
    void scheduledPollingReadsAtMost50RidesEvenWhenManyAreStuck() {
        List<Ride> many = IntStream.range(0, 50).mapToObj(i -> ride("r" + i)).toList();
        when(rideRepository.findByStatusAndLastMatchAttemptAtBefore(any(), any(), any())).thenReturn(many);

        scheduler.retryStuckRides();

        verify(rideService, org.mockito.Mockito.times(50)).retryStuckRide(any());
    }

    private void wireRealService(Ride ride) {
        scheduler = new StuckRideRetryScheduler(rideRepository, realService(), properties, clock);
        when(rideRepository.findByStatusAndLastMatchAttemptAtBefore(any(), any(), any())).thenReturn(List.of(ride));
        when(rideRepository.findById(ride.getId())).thenReturn(Optional.of(ride));
        when(rideRepository.saveAndFlush(ride)).thenReturn(ride);
    }

    private RideService realService() {
        return new RideService(rideRepository, new RideMapper(), fareCalculator, clock, eventPublisher, properties,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private static Ride stuckRide(String id, int attempts) {
        Ride ride = ride(id);
        ride.setMatchAttempts(attempts);
        ride.setLastMatchAttemptAt(NOW.minusSeconds(30));
        return ride;
    }

    private static Ride ride(String id) {
        return Ride.builder().id(id).riderId("rider-" + id).status(RideStatus.MATCHING)
                .pickupLatitude(12.97).pickupLongitude(77.59).pickupAddress("A")
                .dropLatitude(13.02).dropLongitude(77.65).dropAddress("B")
                .lastMatchAttemptAt(NOW.minusSeconds(30))
                .build();
    }
}
