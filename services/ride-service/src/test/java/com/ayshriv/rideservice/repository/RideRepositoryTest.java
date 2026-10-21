package com.ayshriv.rideservice.repository;

import com.ayshriv.rideservice.model.Ride;
import com.ayshriv.rideservice.model.RideStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
