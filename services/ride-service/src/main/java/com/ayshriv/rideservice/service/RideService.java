package com.ayshriv.rideservice.service;

import com.ayshriv.rideservice.dto.RideMapper;
import com.ayshriv.rideservice.dto.RideRequest;
import com.ayshriv.rideservice.dto.RideResponse;
import com.ayshriv.rideservice.dto.RideStatusResponse;
import com.ayshriv.rideservice.exception.InvalidRideStateException;
import com.ayshriv.rideservice.exception.RideNotFoundException;
import com.ayshriv.rideservice.model.Ride;
import com.ayshriv.rideservice.model.RideStatus;
import com.ayshriv.rideservice.repository.RideRepository;
import com.ayshriv.rideservice.messaging.RideRequestedInternalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RideService {

    private final RideRepository rideRepository;
    private final RideMapper rideMapper;
    private final FareCalculator fareCalculator;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Persists the ride as REQUESTED and immediately moves it to MATCHING in the same transaction.
     */
    @Transactional
    public RideResponse requestRide(RideRequest request) {
        BigDecimal estimatedFare = fareCalculator.calculateFare(
                request.getPickupLatitude(), request.getPickupLongitude(),
                request.getDropLatitude(), request.getDropLongitude());

        Ride ride = rideMapper.toEntity(request);
        ride.setStatus(RideStatus.REQUESTED);
        ride.setEstimatedFare(estimatedFare);
        ride = rideRepository.saveAndFlush(ride);

        transition(ride, RideStatus.MATCHING);
        ride = rideRepository.saveAndFlush(ride);

        eventPublisher.publishEvent(new RideRequestedInternalEvent(ride));
        log.info("Ride {} requested by rider {}, estimated fare {}", ride.getId(), ride.getRiderId(), estimatedFare);
        return rideMapper.toResponse(ride);
    }

    @Transactional(readOnly = true)
    public RideResponse getRideById(String rideId) {
        return rideMapper.toResponse(findRide(rideId));
    }

    @Transactional(readOnly = true)
    public List<RideResponse> getRidesByRider(String riderId) {
        return rideRepository.findByRiderIdOrderByCreatedAtDesc(riderId).stream()
                .map(rideMapper::toResponse)
                .toList();
    }

    @Transactional
    public RideResponse startRide(String rideId) {
        Ride ride = findRide(rideId);
        if (ride.getDriverId() == null) {
            throw new InvalidRideStateException("Ride " + rideId + " cannot start without an assigned driver");
        }
        transition(ride, RideStatus.RIDE_STARTED);
        ride.setStartedAt(Instant.now(clock));
        return save(ride);
    }

    @Transactional
    public RideResponse completeRide(String rideId) {
        Ride ride = findRide(rideId);
        transition(ride, RideStatus.COMPLETED);
        ride.setCompletedAt(Instant.now(clock));
        ride.setActualFare(ride.getEstimatedFare());
        return save(ride);
    }

    @Transactional
    public RideResponse cancelRide(String rideId) {
        Ride ride = findRide(rideId);
        transition(ride, RideStatus.CANCELLED);
        return save(ride);
    }

    /**
     * Assigns a matched driver. Idempotent: only a ride still in MATCHING is updated; anything else
     * (unknown ride, already assigned, cancelled, ...) is logged and ignored so that a redelivered
     * or late message does not fail. A concurrent update surfaces as an optimistic-lock failure; callers
     * retry it with {@link OptimisticLockRetry}, which must run outside this transaction.
     */
    @Transactional
    public void assignDriver(String rideId, String driverId) {
        Ride ride = findMatchingRide(rideId, "driver assignment");
        if (ride == null) {
            return;
        }
        ride.setDriverId(driverId);
        transition(ride, RideStatus.ACCEPTED);
        rideRepository.saveAndFlush(ride);
        log.info("Driver {} assigned to ride {}", driverId, rideId);
    }

    /** Same idempotency rules as {@link #assignDriver}: only a ride still in MATCHING can become NO_DRIVER_FOUND. */
    @Transactional
    public void markNoDriverFound(String rideId, String reason) {
        Ride ride = findMatchingRide(rideId, "no-driver result");
        if (ride == null) {
            return;
        }
        transition(ride, RideStatus.NO_DRIVER_FOUND);
        rideRepository.saveAndFlush(ride);
        log.info("Ride {} marked NO_DRIVER_FOUND: {}", rideId, reason);
    }

    @Transactional(readOnly = true)
    public RideStatusResponse getRideStatus(String rideId) {
        Ride ride = findRide(rideId);
        return new RideStatusResponse(ride.getId(), ride.getStatus(), ride.getDriverId());
    }

    /** Returns the ride only if it exists and is MATCHING; otherwise logs and returns null (never throws). */
    private Ride findMatchingRide(String rideId, String what) {
        Optional<Ride> found = rideRepository.findById(rideId);
        if (found.isEmpty()) {
            log.warn("Ignoring {}: ride {} not found", what, rideId);
            return null;
        }
        Ride ride = found.get();
        if (ride.getStatus() != RideStatus.MATCHING) {
            log.warn("Ignoring {} for ride {}: status is {}, expected MATCHING", what, rideId, ride.getStatus());
            return null;
        }
        return ride;
    }

    /** Single place where status changes are validated against {@link RideStatus#canTransitionTo}. */
    private void transition(Ride ride, RideStatus next) {
        if (!ride.getStatus().canTransitionTo(next)) {
            throw new InvalidRideStateException(ride.getId(), ride.getStatus(), next);
        }
        log.debug("Ride {}: {} -> {}", ride.getId(), ride.getStatus(), next);
        ride.setStatus(next);
    }

    private Ride findRide(String rideId) {
        return rideRepository.findById(rideId).orElseThrow(() -> new RideNotFoundException(rideId));
    }

    /** Flushes so the response carries the new version and updatedAt. */
    private RideResponse save(Ride ride) {
        return rideMapper.toResponse(rideRepository.saveAndFlush(ride));
    }
}
