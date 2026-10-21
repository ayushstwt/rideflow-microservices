package com.ayshriv.matchingservice.service;

import com.ayshriv.matchingservice.config.MatchingProperties;
import com.ayshriv.matchingservice.dto.NearbyDriverResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DriverScorerTest {

    private final MatchingProperties properties = new MatchingProperties();
    private final DriverScorer scorer = new DriverScorer(properties, new StaticDriverRatingProvider());

    @Test
    void score_followsTheFormula() {
        // 0.7 * (1 - 1/5) + 0.3 * (4.5 / 5) = 0.56 + 0.27
        assertThat(scorer.score(driver("d1", 1.0))).isCloseTo(0.83, within(1e-9));
    }

    @Test
    void score_distanceBeyondRadiusIsClampedToZeroDistanceScore() {
        assertThat(scorer.score(driver("d1", 50.0))).isCloseTo(0.27, within(1e-9));
    }

    @Test
    void rank_closerDriverWins() {
        List<ScoredDriver> ranked = scorer.rank(List.of(driver("far", 3.0), driver("near", 0.5), driver("mid", 1.5)));

        assertThat(ranked).extracting(s -> s.driver().getDriverId()).containsExactly("near", "mid", "far");
    }

    @Test
    void rank_higherRatingCanOutweighSmallDistanceGap() {
        DriverRatingProvider ratings = id -> Map.of("rated", 5.0, "unrated", 1.0).get(id);
        DriverScorer custom = new DriverScorer(properties, ratings);

        List<ScoredDriver> ranked = custom.rank(List.of(driver("unrated", 1.0), driver("rated", 1.2)));

        assertThat(ranked.get(0).driver().getDriverId()).isEqualTo("rated");
    }

    @Test
    void rank_equalScoreAndDistanceFallsBackToDriverIdAlphabetically() {
        List<ScoredDriver> ranked = scorer.rank(List.of(driver("d-c", 1.0), driver("d-a", 1.0), driver("d-b", 1.0)));

        assertThat(ranked).extracting(s -> s.driver().getDriverId()).containsExactly("d-a", "d-b", "d-c");
    }

    @Test
    void rank_equalScoreFallsBackToSmallerDistance() {
        // Both are outside the radius, so both distance scores are clamped to 0 and the scores tie.
        List<ScoredDriver> ranked = scorer.rank(List.of(driver("a", 9.0), driver("z", 7.0)));

        assertThat(ranked.get(0).score()).isEqualTo(ranked.get(1).score());
        assertThat(ranked).extracting(s -> s.driver().getDriverId()).containsExactly("z", "a");
    }

    @Test
    void rank_isIdenticalOverRepeatedRunsAndInputOrder() {
        List<NearbyDriverResponse> input = List.of(
                driver("d3", 2.0), driver("d1", 2.0), driver("d2", 0.7), driver("d5", 6.0), driver("d4", 6.5));
        List<ScoredDriver> first = scorer.rank(input);

        for (int i = 0; i < 100; i++) {
            assertThat(scorer.rank(input)).isEqualTo(first);
        }
        List<NearbyDriverResponse> reversed = new ArrayList<>(input);
        Collections.reverse(reversed);
        assertThat(scorer.rank(reversed)).isEqualTo(first);
    }

    private static NearbyDriverResponse driver(String id, double distanceKm) {
        return NearbyDriverResponse.builder().driverId(id).latitude(12.9).longitude(77.5)
                .distanceInKm(distanceKm).build();
    }
}
