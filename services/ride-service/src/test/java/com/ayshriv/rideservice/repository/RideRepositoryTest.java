package com.ayshriv.rideservice.repository;

import com.ayshriv.rideservice.model.Ride;
import com.ayshriv.rideservice.model.RideStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the entity mapping against in-memory H2 (MySQL mode); no external database needed. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RideRepositoryTest {

    @Autowired
    private RideRepository rideRepository;

    @Test
    void savesRideWithGeneratedIdAndAuditFields() {
        Ride saved = rideRepository.saveAndFlush(ride("rider-1"));

        assertThat(saved.getId()).hasSize(36);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isNotNull();
    }

    @Test
    void findByRiderId_returnsOnlyThatRidersRides() {
        rideRepository.save(ride("rider-1"));
        rideRepository.save(ride("rider-1"));
        rideRepository.save(ride("rider-2"));
        rideRepository.flush();

        List<Ride> rides = rideRepository.findByRiderIdOrderByCreatedAtDesc("rider-1");

        assertThat(rides).hasSize(2).allMatch(ride -> ride.getRiderId().equals("rider-1"));
    }

    @Test
    void newRideStartsWithZeroMatchAttemptsAndNoKey() {
        Ride saved = rideRepository.saveAndFlush(ride("rider-1"));

        assertThat(saved.getMatchAttempts()).isZero();
        assertThat(saved.getLastMatchAttemptAt()).isNull();
        assertThat(saved.getIdempotencyKey()).isNull();
        assertThat(saved.getLastFailureReason()).isNull();
    }

    @Test
    void uniqueConstraintRejectsTheSameRiderAndIdempotencyKey() {
        rideRepository.saveAndFlush(withKey(ride("rider-1"), "key-1"));

        assertThatThrownBy(() -> rideRepository.saveAndFlush(withKey(ride("rider-1"), "key-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameKeyIsAllowedForADifferentRiderAndNullKeysNeverCollide() {
        rideRepository.saveAndFlush(withKey(ride("rider-1"), "key-1"));
        rideRepository.saveAndFlush(withKey(ride("rider-2"), "key-1"));
        rideRepository.saveAndFlush(ride("rider-1"));
        rideRepository.saveAndFlush(ride("rider-1"));

        assertThat(rideRepository.findByRiderIdAndIdempotencyKey("rider-2", "key-1")).isPresent();
        assertThat(rideRepository.findByRiderIdAndIdempotencyKey("rider-1", "key-1")).isPresent()
                .get().extracting(Ride::getRiderId).isEqualTo("rider-1");
        assertThat(rideRepository.findByRiderIdAndIdempotencyKey("rider-1", "other")).isEmpty();
    }

    @Test
    void activeRideQuery_findsEveryActiveStatusButNoFinishedOne() {
        for (RideStatus status : RideStatus.values()) {
            Ride saved = rideRepository.saveAndFlush(withStatus(ride("rider-" + status), status));

            assertThat(rideRepository.findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(
                    "rider-" + status, RideStatus.ACTIVE_STATUSES))
                    .as(status.name())
                    .isEqualTo(status.isTerminal() ? java.util.Optional.empty() : java.util.Optional.of(saved));
        }
    }

    @Test
    void activeRideQuery_isEmptyAfterCompletedAndCancelledAndFindsALaterActiveRide() {
        rideRepository.saveAndFlush(withStatus(ride("rider-1"), RideStatus.COMPLETED));
        rideRepository.saveAndFlush(withStatus(ride("rider-1"), RideStatus.CANCELLED));
        assertThat(rideRepository.findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(
                "rider-1", RideStatus.ACTIVE_STATUSES)).isEmpty();

        Ride active = rideRepository.saveAndFlush(withStatus(ride("rider-1"), RideStatus.MATCHING));

        assertThat(rideRepository.findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(
                "rider-1", RideStatus.ACTIVE_STATUSES)).contains(active);
    }

    @Test
    void stuckRideQuery_returnsOnlyOldMatchingRidesOldestFirstAndHonoursTheLimit() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        Ride oldest = rideRepository.save(matching("rider-1", now.minusSeconds(120)));
        Ride old = rideRepository.save(matching("rider-2", now.minusSeconds(60)));
        rideRepository.save(matching("rider-3", now.minusSeconds(5)));                                  // too recent
        rideRepository.save(withStatus(matching("rider-4", now.minusSeconds(120)), RideStatus.ACCEPTED)); // not MATCHING
        rideRepository.save(matching("rider-5", null));                                                  // never attempted
        rideRepository.flush();
        Instant cutoff = now.minusSeconds(15);
        Sort oldestFirst = Sort.by("lastMatchAttemptAt").ascending();

        assertThat(rideRepository.findByStatusAndLastMatchAttemptAtBefore(
                RideStatus.MATCHING, cutoff, PageRequest.of(0, 50, oldestFirst)))
                .containsExactly(oldest, old);
        assertThat(rideRepository.findByStatusAndLastMatchAttemptAtBefore(
                RideStatus.MATCHING, cutoff, PageRequest.of(0, 1, oldestFirst)))
                .containsExactly(oldest);
    }

    private static Ride matching(String riderId, Instant lastAttemptAt) {
        Ride ride = withStatus(ride(riderId), RideStatus.MATCHING);
        ride.setMatchAttempts(1);
        ride.setLastMatchAttemptAt(lastAttemptAt);
        return ride;
    }

    private static Ride withKey(Ride ride, String key) {
        ride.setIdempotencyKey(key);
        return ride;
    }

    private static Ride withStatus(Ride ride, RideStatus status) {
        ride.setStatus(status);
        return ride;
    }

    private static Ride ride(String riderId) {
        return Ride.builder()
                .riderId(riderId)
                .pickupLatitude(12.97).pickupLongitude(77.59).pickupAddress("A")
                .dropLatitude(13.02).dropLongitude(77.65).dropAddress("B")
                .status(RideStatus.REQUESTED)
                .estimatedFare(new BigDecimal("150.00"))
                .build();
    }
}
