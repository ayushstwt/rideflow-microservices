package com.ayshriv.matchingservice.service;

import com.ayshriv.matchingservice.config.MatchingProperties;
import com.ayshriv.matchingservice.dto.NearbyDriverResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Deterministic ranking: {@code score = wDistance * (1 - min(distanceKm / radiusKm, 1)) + wRating * (rating / 5)}.
 * Ties are broken by smaller distance, then by driverId alphabetically.
 */
@Component
public class DriverScorer {

    private static final double MAX_RATING = 5.0;

    private static final Comparator<ScoredDriver> BEST_FIRST = Comparator
            .comparingDouble(ScoredDriver::score).reversed()
            .thenComparingDouble((ScoredDriver s) -> s.driver().getDistanceInKm())
            .thenComparing(s -> s.driver().getDriverId());

    private final MatchingProperties properties;
    private final DriverRatingProvider ratingProvider;

    public DriverScorer(MatchingProperties properties, DriverRatingProvider ratingProvider) {
        this.properties = properties;
        this.ratingProvider = ratingProvider;
    }

    public double score(NearbyDriverResponse driver) {
        double distanceScore = 1.0 - Math.min(driver.getDistanceInKm() / properties.getSearchRadiusKm(), 1.0);
        double ratingScore = ratingProvider.getRating(driver.getDriverId()) / MAX_RATING;
        return properties.getWeights().getDistance() * distanceScore
                + properties.getWeights().getRating() * ratingScore;
    }

    /** Best candidate first. */
    public List<ScoredDriver> rank(List<NearbyDriverResponse> candidates) {
        return candidates.stream()
                .map(driver -> new ScoredDriver(driver, score(driver)))
                .sorted(BEST_FIRST)
                .toList();
    }
}
