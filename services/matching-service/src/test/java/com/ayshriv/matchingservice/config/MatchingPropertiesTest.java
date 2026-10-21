package com.ayshriv.matchingservice.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValid() {
        assertThat(validator.validate(new MatchingProperties())).isEmpty();
    }

    @Test
    void weightsThatDoNotSumToOneAreRejected() {
        MatchingProperties properties = new MatchingProperties();
        properties.getWeights().setDistance(0.8);
        properties.getWeights().setRating(0.3);

        assertThat(validator.validate(properties))
                .extracting(v -> v.getMessage())
                .anyMatch(m -> m.contains("must equal 1.0"));
    }

    @Test
    void candidateLimitAboveLocationServiceMaximumIsRejected() {
        MatchingProperties properties = new MatchingProperties();
        properties.setMaxCandidates(51);

        assertThat(validator.validate(properties)).isNotEmpty();
    }
}
