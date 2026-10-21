package com.ayshriv.matchingservice.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "matching")
public class MatchingProperties {

    private static final double TOLERANCE = 1e-6;

    /** location-service accepts 0.1 - 50 km. */
    @DecimalMin("0.1")
    @DecimalMax("50.0")
    private double searchRadiusKm = 5.0;

    /** location-service accepts 1 - 50 results. */
    @Min(1)
    @Max(50)
    private int maxCandidates = 10;

    @Valid
    private Weights weights = new Weights();

    @AssertTrue(message = "matching.weights.distance + matching.weights.rating must equal 1.0")
    public boolean isWeightsSumToOne() {
        return Math.abs(weights.getDistance() + weights.getRating() - 1.0) < TOLERANCE;
    }

    @Getter
    @Setter
    public static class Weights {
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double distance = 0.7;
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double rating = 0.3;
    }
}
