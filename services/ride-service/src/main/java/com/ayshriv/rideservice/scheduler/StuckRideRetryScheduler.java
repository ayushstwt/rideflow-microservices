package com.ayshriv.rideservice.scheduler;

import com.ayshriv.rideservice.config.RideMatchingProperties;
import com.ayshriv.rideservice.model.Ride;
import com.ayshriv.rideservice.model.RideStatus;
import com.ayshriv.rideservice.repository.RideRepository;
import com.ayshriv.rideservice.service.RideService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Re-drives rides that sit in MATCHING without a result: retries matching, or gives up once the attempts are
 * used up. This also recovers rides whose ride.requested or result message was lost.
 * <p>
 * Safe to run on several instances: each ride is handled in its own transaction and the {@code @Version}
 * column makes the second instance's update fail instead of retrying the ride twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckRideRetryScheduler {

    /** Upper bound of rides handled per run; the rest are picked up by the next run. */
    static final int BATCH_SIZE = 50;

    private final RideRepository rideRepository;
    private final RideService rideService;
    private final RideMatchingProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${ride.matching.scheduler-interval-seconds:10}", timeUnit = TimeUnit.SECONDS)
    public void retryStuckRides() {
        Instant cutoff = clock.instant().minusSeconds(properties.getRetryDelaySeconds());
        List<Ride> stuck = rideRepository.findByStatusAndLastMatchAttemptAtBefore(RideStatus.MATCHING, cutoff,
                PageRequest.of(0, BATCH_SIZE, Sort.by("lastMatchAttemptAt").ascending()));
        if (stuck.isEmpty()) {
            return;
        }
        log.info("Found {} ride(s) waiting for a driver longer than {}s", stuck.size(), properties.getRetryDelaySeconds());

        for (Ride ride : stuck) {
            try {
                rideService.retryStuckRide(ride.getId());
            } catch (OptimisticLockingFailureException e) {
                // Another instance or an incoming match result updated the ride first; nothing left to do here.
                log.debug("Ride {}: lost the race while retrying, skipping", ride.getId());
            } catch (RuntimeException e) {
                // One bad ride must not block the rest of the batch.
                log.error("Ride {}: match retry failed", ride.getId(), e);
            }
        }
    }
}
