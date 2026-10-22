package com.ayshriv.rideservice.repository;

import com.ayshriv.rideservice.model.Ride;
import com.ayshriv.rideservice.model.RideStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RideRepository extends JpaRepository<Ride, String> {

    List<Ride> findByRiderIdOrderByCreatedAtDesc(String riderId);

    Optional<Ride> findByRiderIdAndIdempotencyKey(String riderId, String idempotencyKey);

    Optional<Ride> findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(String riderId, Collection<RideStatus> statuses);

    /** Rides in {@code status} whose last match attempt is older than {@code cutoff}; page size bounds a scheduler run. */
    List<Ride> findByStatusAndLastMatchAttemptAtBefore(RideStatus status, Instant cutoff, Pageable pageable);
}
