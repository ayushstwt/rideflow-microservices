package com.rideshare.rideservice.service;

import com.rideshare.rideservice.exception.InvalidRideRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FareCalculatorTest {

    private final FareCalculator fareCalculator = new FareCalculator();

    @Test
    void distanceKm_oneDegreeOfLatitudeIsAbout111Km() {
        assertThat(fareCalculator.distanceKm(0, 0, 1, 0)).isCloseTo(111.19, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void calculateFare_isBasePlusRatePerKmRoundedToTwoDecimals() {
        // 111.1949... km * 12 + 50 = 1384.339... -> 1384.34
        BigDecimal fare = fareCalculator.calculateFare(0, 0, 1, 0);

        assertThat(fare).isEqualByComparingTo("1384.34");
        assertThat(fare.scale()).isEqualTo(2);
    }

    @Test
    void calculateFare_appliesMinimumFareForVeryShortTrips() {
        // ~0.11 km would be 51.33 without the minimum
        BigDecimal fare = fareCalculator.calculateFare(0, 0, 0, 0.001);

        assertThat(fare).isEqualByComparingTo("60.00");
        assertThat(fare.scale()).isEqualTo(2);
    }

    @Test
    void calculateFare_rejectsIdenticalPickupAndDrop() {
        assertThatThrownBy(() -> fareCalculator.calculateFare(12.97, 77.59, 12.97, 77.59))
                .isInstanceOf(InvalidRideRequestException.class)
                .hasMessageContaining("different");
    }
}
