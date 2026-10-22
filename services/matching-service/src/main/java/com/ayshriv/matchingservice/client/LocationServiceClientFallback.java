package com.ayshriv.matchingservice.client;

import com.ayshriv.matchingservice.dto.NearbyDriverResponse;
import com.ayshriv.matchingservice.dto.ReserveRequest;
import com.ayshriv.matchingservice.dto.ReserveResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Used when location-service fails, the circuit is open, or it answers 409 (every candidate taken):
 * no candidates / no reservation, so the ride is reported as unmatched.
 */
@Slf4j
@Component
public class LocationServiceClientFallback implements LocationServiceClient {

    @Override
    public List<NearbyDriverResponse> findNearbyDrivers(double latitude, double longitude, double radius, int limit) {
        log.warn("location-service unavailable, returning no drivers for pickup ({}, {}) radius {} km",
                latitude, longitude, radius);
        return List.of();
    }

    @Override
    public Optional<ReserveResponse> reserve(ReserveRequest request) {
        log.warn("Ride {}: driver reservation failed or no driver free, returning no reservation",
                request.getRideId());
        return Optional.empty();
    }
}
