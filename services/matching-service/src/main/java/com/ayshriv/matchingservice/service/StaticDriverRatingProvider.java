package com.ayshriv.matchingservice.service;

import org.springframework.stereotype.Component;

/**
 * PLACEHOLDER until a driver-service exists: every driver gets the same fixed rating, so for now the
 * ranking is decided by distance alone. Replace with a client for the real rating source.
 */
@Component
public class StaticDriverRatingProvider implements DriverRatingProvider {

    static final double DEFAULT_RATING = 4.5;

    @Override
    public double getRating(String driverId) {
        return DEFAULT_RATING;
    }
}
