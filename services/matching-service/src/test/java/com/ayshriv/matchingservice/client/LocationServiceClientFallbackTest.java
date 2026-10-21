package com.ayshriv.matchingservice.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocationServiceClientFallbackTest {

    @Test
    void returnsEmptyListInsteadOfFailing() {
        assertThat(new LocationServiceClientFallback().findNearbyDrivers(12.97, 77.59, 5.0, 10)).isEmpty();
    }
}
