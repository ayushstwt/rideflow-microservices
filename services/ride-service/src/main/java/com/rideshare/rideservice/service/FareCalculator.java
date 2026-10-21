package com.rideshare.rideservice.service;

import com.rideshare.rideservice.exception.InvalidRideRequestException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fare = base fare + per-km rate over the straight-line (Haversine) distance, with a minimum fare.
 */
@Component
public class FareCalculator {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final BigDecimal BASE_FARE = BigDecimal.valueOf(50);
    private static final BigDecimal RATE_PER_KM = BigDecimal.valueOf(12);
    private static final BigDecimal MINIMUM_FARE = BigDecimal.valueOf(60);

    public BigDecimal calculateFare(double pickupLat, double pickupLng, double dropLat, double dropLng) {
        if (Double.compare(pickupLat, dropLat) == 0 && Double.compare(pickupLng, dropLng) == 0) {
            throw new InvalidRideRequestException("Pickup and drop locations must be different");
        }
        BigDecimal distanceKm = BigDecimal.valueOf(distanceKm(pickupLat, pickupLng, dropLat, dropLng));
        BigDecimal fare = BASE_FARE.add(RATE_PER_KM.multiply(distanceKm));
        return fare.max(MINIMUM_FARE).setScale(2, RoundingMode.HALF_UP);
    }

    /** Great-circle distance between two points using the Haversine formula. */
    public double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
