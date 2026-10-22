package com.ayshriv.matchingservice.service;

import com.ayshriv.events.RideRequestedEvent;
import com.ayshriv.matchingservice.client.LocationServiceClient;
import com.ayshriv.matchingservice.config.MatchingProperties;
import com.ayshriv.matchingservice.dto.NearbyDriverResponse;
import com.ayshriv.matchingservice.dto.ReserveRequest;
import com.ayshriv.matchingservice.dto.ReserveResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingService {

    /** location-service rejects reserve requests with more candidates than this. */
    static final int MAX_RESERVE_CANDIDATES = 20;

    private final LocationServiceClient locationClient;
    private final DriverScorer scorer;
    private final MatchingProperties properties;

    /**
     * Finds the best free driver near the pickup and reserves them. Does not publish anything.
     * <p>
     * Candidates are sent to location-service best first and the driver it returns is the match, which may
     * not be the top-scored one if that driver was reserved by another ride in the meantime. The same ride
     * always gets the same driver back, so a redelivered {@code ride.requested} is harmless.
     */
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

        List<ScoredDriver> ranked = scorer.rank(candidates);
        ScoredDriver best = ranked.get(0);
        log.info("Ride {}: best of {} candidates is driver {} (score {}, {} km)",
                event.getRideId(), candidates.size(), best.driver().getDriverId(), best.score(),
                best.driver().getDistanceInKm());

        List<String> orderedIds = ranked.stream()
                .map(scored -> scored.driver().getDriverId())
                .limit(MAX_RESERVE_CANDIDATES)
                .toList();
        Optional<String> reservedId = reserve(event.getRideId(), orderedIds);
        if (reservedId.isEmpty()) {
            log.info("Ride {}: no candidate driver could be reserved", event.getRideId());
            return Optional.empty();
        }

        String driverId = reservedId.get();
        Optional<NearbyDriverResponse> reserved = candidates.stream()
                .filter(candidate -> driverId.equals(candidate.getDriverId()))
                .findFirst();
        if (reserved.isEmpty()) {
            // The ride already held this driver from an earlier delivery, and reserved drivers are hidden
            // from the nearby results, so their position is unknown here.
            log.info("Ride {}: driver {} was already reserved for this ride", event.getRideId(), driverId);
            return Optional.of(new Match(driverId, null, null, null));
        }
        NearbyDriverResponse driver = reserved.get();
        log.info("Ride {}: reserved driver {}{}", event.getRideId(), driverId,
                driverId.equals(best.driver().getDriverId()) ? "" : " (top-scored driver was taken)");
        return Optional.of(new Match(driverId, driver.getLatitude(), driver.getLongitude(),
                driver.getDistanceInKm()));
    }

    /** Empty on 409 (every candidate taken), a null/blank answer, or the circuit-breaker fallback. */
    private Optional<String> reserve(String rideId, List<String> orderedDriverIds) {
        try {
            return locationClient.reserve(new ReserveRequest(rideId, orderedDriverIds))
                    .map(ReserveResponse::getDriverId)
                    .filter(id -> !id.isBlank());
        } catch (FeignException.Conflict e) {
            log.info("Ride {}: location-service has no free driver among {} candidate(s)", rideId,
                    orderedDriverIds.size());
            return Optional.empty();
        }
    }
}
