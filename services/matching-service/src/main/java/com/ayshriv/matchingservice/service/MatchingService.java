package com.ayshriv.matchingservice.service;

import com.ayshriv.events.RideRequestedEvent;
import com.ayshriv.matchingservice.client.LocationServiceClient;
import com.ayshriv.matchingservice.config.MatchingProperties;
import com.ayshriv.matchingservice.dto.NearbyDriverResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingService {

    private final LocationServiceClient locationClient;
    private final DriverScorer scorer;
    private final MatchingProperties properties;

    /** Finds the best driver near the pickup. Does not publish anything. */
    public Optional<Match> findMatch(RideRequestedEvent event) {
        if (event.getPickupLatitude() == null || event.getPickupLongitude() == null) {
            throw new IllegalArgumentException("Ride " + event.getRideId() + " has no pickup coordinates");
        }

        List<NearbyDriverResponse> candidates = locationClient.findNearbyDrivers(
                event.getPickupLatitude(), event.getPickupLongitude(),
                properties.getSearchRadiusKm(), properties.getMaxCandidates());

        if (candidates == null || candidates.isEmpty()) {
            log.info("Ride {}: no drivers within {} km", event.getRideId(), properties.getSearchRadiusKm());
            return Optional.empty();
        }

        ScoredDriver best = scorer.rank(candidates).get(0);
        NearbyDriverResponse driver = best.driver();
        log.info("Ride {}: best of {} candidates is driver {} (score {}, {} km)",
                event.getRideId(), candidates.size(), driver.getDriverId(), best.score(), driver.getDistanceInKm());
        return Optional.of(new Match(driver.getDriverId(), driver.getLatitude(), driver.getLongitude(),
                driver.getDistanceInKm()));
    }
}
