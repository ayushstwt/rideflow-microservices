package com.ayshriv.rideservice.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Retry policy for rides that are waiting for a driver. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ride.matching")
public class RideMatchingProperties {

    /** Total matching attempts (the first one included) before the ride becomes NO_DRIVER_FOUND. */
    @Min(1)
    private int maxAttempts = 3;

    /** A MATCHING ride without a result for this long is retried by the scheduler. */
    @Min(1)
    private long retryDelaySeconds = 15;

    /** Pause between two scheduler runs. */
    @Min(1)
    private long schedulerIntervalSeconds = 10;
}
