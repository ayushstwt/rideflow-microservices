package com.ayshriv.rideservice.service;

import com.ayshriv.events.RideClosedEvent;
import com.ayshriv.rideservice.config.RideMatchingProperties;
import com.ayshriv.rideservice.dto.RideMapper;
import com.ayshriv.rideservice.dto.RideRequest;
import com.ayshriv.rideservice.dto.RideResponse;
import com.ayshriv.rideservice.dto.RideStatusResponse;
import com.ayshriv.rideservice.exception.ActiveRideExistsException;
import com.ayshriv.rideservice.exception.InvalidRideStateException;
import com.ayshriv.rideservice.exception.RideNotFoundException;
import com.ayshriv.rideservice.model.Ride;
import com.ayshriv.rideservice.model.RideStatus;
import com.ayshriv.rideservice.repository.RideRepository;
import com.ayshriv.rideservice.messaging.RideClosedInternalEvent;
import com.ayshriv.rideservice.messaging.RideRequestedInternalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final RideMatchingProperties matchingProperties;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates the ride (REQUESTED, then MATCHING, in one transaction), or replays an earlier request.
     * <p>
     * With an {@code idempotencyKey}, a ride the same rider already created with that key is returned
     * unchanged ({@code created == false}) and the active-ride rule is skipped. Two identical requests racing
     * past that check collide on the unique (rider, key) constraint; the loser's transaction is unusable after
     * the violation, so the winning ride is loaded here, outside of it. That is why this method manages the
     * transaction itself instead of using {@code @Transactional}.
     */
    public RideRequestResult requestRide(RideRequest request, String idempotencyKey) {
        try {
            return transactionTemplate.execute(status -> createRide(request, idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey != null) {
                Optional<Ride> existing = rideRepository.findByRiderIdAndIdempotencyKey(request.getRiderId(), idempotencyKey);
                if (existing.isPresent()) {
                    log.info("Ride {}: concurrent duplicate request for rider {} resolved to the existing ride",
                            existing.get().getId(), request.getRiderId());
                    return new RideRequestResult(rideMapper.toResponse(existing.get()), false);
                }
            }
            throw e;
        }
    }

    private RideRequestResult createRide(RideRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Ride> existing = rideRepository.findByRiderIdAndIdempotencyKey(request.getRiderId(), idempotencyKey);
            if (existing.isPresent()) {
                log.info("Ride {}: replaying request with Idempotency-Key for rider {}",
                        existing.get().getId(), request.getRiderId());
                return new RideRequestResult(rideMapper.toResponse(existing.get()), false);
            }
        }

        rideRepository.findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(request.getRiderId(), RideStatus.ACTIVE_STATUSES)
                .ifPresent(active -> {
                    throw new ActiveRideExistsException(request.getRiderId(), active.getId());
                });

        BigDecimal estimatedFare = fareCalculator.calculateFare(
                request.getPickupLatitude(), request.getPickupLongitude(),
                request.getDropLatitude(), request.getDropLongitude());

        Ride ride = rideMapper.toEntity(request);
        ride.setStatus(RideStatus.REQUESTED);
        ride.setEstimatedFare(estimatedFare);
        ride.setIdempotencyKey(idempotencyKey);
        ride = rideRepository.saveAndFlush(ride);

        transition(ride, RideStatus.MATCHING);
        ride.setMatchAttempts(1);
        ride.setLastMatchAttemptAt(Instant.now(clock));
        ride = rideRepository.saveAndFlush(ride);

        eventPublisher.publishEvent(new RideRequestedInternalEvent(ride));
        log.info("Ride {} requested by rider {}, estimated fare {}", ride.getId(), ride.getRiderId(), estimatedFare);
        return new RideRequestResult(rideMapper.toResponse(ride), true);
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

    /**
     * Same idempotency rules as {@link #assignDriver}: only a ride still in MATCHING is touched. While attempts
     * are left the ride stays in MATCHING and {@link #retryStuckRide} tries again after the retry delay; only
     * when the last attempt failed does it become NO_DRIVER_FOUND.
     */
    @Transactional
    public void markNoDriverFound(String rideId, String reason) {
        Ride ride = findMatchingRide(rideId, "no-driver result");
        if (ride == null) {
            return;
        }
        ride.setLastFailureReason(truncate(reason));
        if (ride.getMatchAttempts() < matchingProperties.getMaxAttempts()) {
            rideRepository.saveAndFlush(ride);
            log.info("Ride {}: attempt {}/{} found no driver ({}), will retry", rideId,
                    ride.getMatchAttempts(), matchingProperties.getMaxAttempts(), reason);
            return;
        }
        transition(ride, RideStatus.NO_DRIVER_FOUND);
        rideRepository.saveAndFlush(ride);
        log.info("Ride {} marked NO_DRIVER_FOUND after {} attempts: {}", rideId, ride.getMatchAttempts(), reason);
    }

    /**
     * One scheduler step for a ride that has been in MATCHING without a result: gives up when the attempts are
     * used up, otherwise counts a new attempt and re-publishes ride.requested after commit. Re-checks the ride
     * in its own transaction so a result that arrived meanwhile, or another instance that already retried it,
     * is respected; a lost race surfaces as an optimistic-lock failure that the caller treats as "someone else did it".
     */
    @Transactional
    public void retryStuckRide(String rideId) {
        Ride ride = findMatchingRide(rideId, "match retry");
        if (ride == null) {
            return;
        }
        Instant now = Instant.now(clock);
        Instant lastAttempt = ride.getLastMatchAttemptAt();
        if (lastAttempt != null && lastAttempt.isAfter(now.minusSeconds(matchingProperties.getRetryDelaySeconds()))) {
            log.debug("Ride {}: retried recently, skipping", rideId);
            return;
        }

        if (ride.getMatchAttempts() >= matchingProperties.getMaxAttempts()) {
            if (ride.getLastFailureReason() == null) {
                ride.setLastFailureReason("NO_MATCH_RESULT");
            }
            transition(ride, RideStatus.NO_DRIVER_FOUND);
            rideRepository.saveAndFlush(ride);
            log.warn("Ride {}: no driver after {} attempts, giving up", rideId, ride.getMatchAttempts());
            return;
        }

        ride.setMatchAttempts(ride.getMatchAttempts() + 1);
        ride.setLastMatchAttemptAt(now);
        rideRepository.saveAndFlush(ride);
        eventPublisher.publishEvent(new RideRequestedInternalEvent(ride));
        log.info("Ride {}: retrying match, attempt {}/{}", rideId, ride.getMatchAttempts(),
                matchingProperties.getMaxAttempts());
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

    /**
     * Single place where status changes happen, so it is also the single place that announces a closed ride:
     * every move into a terminal status (COMPLETED, CANCELLED, NO_DRIVER_FOUND) publishes the ride.closed
     * event that releases the reserved driver. Never set a ride's status anywhere else, or that path would
     * forget to release the driver.
     */
    private void transition(Ride ride, RideStatus next) {
        if (!ride.getStatus().canTransitionTo(next)) {
            throw new InvalidRideStateException(ride.getId(), ride.getStatus(), next);
        }
        log.debug("Ride {}: {} -> {}", ride.getId(), ride.getStatus(), next);
        ride.setStatus(next);
        if (next.isTerminal()) {
            eventPublisher.publishEvent(new RideClosedInternalEvent(RideClosedEvent.builder()
                    .rideId(ride.getId())
                    .riderId(ride.getRiderId())
                    .driverId(ride.getDriverId())
                    .finalStatus(next.name())
                    .closedAt(Instant.now(clock))
                    .build()));
        }
    }

    /** lastFailureReason is a plain VARCHAR(255) column. */
    private static String truncate(String reason) {
        return reason != null && reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    private Ride findRide(String rideId) {
        return rideRepository.findById(rideId).orElseThrow(() -> new RideNotFoundException(rideId));
    }

    /** Flushes so the response carries the new version and updatedAt. */
    private RideResponse save(Ride ride) {
        return rideMapper.toResponse(rideRepository.saveAndFlush(ride));
    }
}
