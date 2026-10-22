package com.ayshriv.matchingservice.client;

import com.ayshriv.matchingservice.dto.ReserveRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocationServiceClientFallbackTest {

    @Test
    void returnsEmptyListInsteadOfFailing() {
        assertThat(new LocationServiceClientFallback().findNearbyDrivers(12.97, 77.59, 5.0, 10)).isEmpty();
    }

    @Test
    void reserveReturnsEmptyInsteadOfFailing() {
        assertThat(new LocationServiceClientFallback().reserve(new ReserveRequest("ride-1", List.of("d1")))).isEmpty();
    }
}
