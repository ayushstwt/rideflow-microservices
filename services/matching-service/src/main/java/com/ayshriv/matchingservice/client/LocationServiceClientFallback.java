package com.ayshriv.matchingservice.client;

import com.ayshriv.matchingservice.dto.NearbyDriverResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/** Used when location-service fails or the circuit is open: no candidates, so the ride is reported as unmatched. */
@Slf4j
@Component
public class LocationServiceClientFallback implements LocationServiceClient {

    @Override
    public List<NearbyDriverResponse> findNearbyDrivers(double latitude, double longitude, double radius, int limit) {
        log.warn("location-service unavailable, returning no drivers for pickup ({}, {}) radius {} km",
                latitude, longitude, radius);
        return List.of();
    }
}
