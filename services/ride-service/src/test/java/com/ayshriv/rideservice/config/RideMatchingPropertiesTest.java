package com.ayshriv.rideservice.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RideMatchingPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsMatchThePolicyAndAreValid() {
        RideMatchingProperties properties = new RideMatchingProperties();

        assertThat(properties.getMaxAttempts()).isEqualTo(3);
        assertThat(properties.getRetryDelaySeconds()).isEqualTo(15);
        assertThat(properties.getSchedulerIntervalSeconds()).isEqualTo(10);
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsValuesBelowOne() {
        RideMatchingProperties properties = new RideMatchingProperties();
        properties.setMaxAttempts(0);
        properties.setRetryDelaySeconds(0);
        properties.setSchedulerIntervalSeconds(-1);

        assertThat(validator.validate(properties)).hasSize(3);
    }
}
