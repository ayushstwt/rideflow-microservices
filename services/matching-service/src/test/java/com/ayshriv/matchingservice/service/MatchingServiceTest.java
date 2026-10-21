package com.ayshriv.matchingservice.service;

import com.ayshriv.events.RideRequestedEvent;
import com.ayshriv.matchingservice.client.LocationServiceClient;
import com.ayshriv.matchingservice.config.MatchingProperties;
import com.ayshriv.matchingservice.dto.NearbyDriverResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    @Mock
    private LocationServiceClient locationClient;

    private MatchingService matchingService;

    @BeforeEach
    void setUp() {
        MatchingProperties properties = new MatchingProperties();
        matchingService = new MatchingService(locationClient,
                new DriverScorer(properties, new StaticDriverRatingProvider()), properties);
    }

    @Test
    void findMatch_returnsBestScoredDriver() {
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(List.of(
                driver("far", 12.99, 77.60, 3.0), driver("near", 12.971, 77.591, 0.4)));

        Optional<Match> match = matchingService.findMatch(event());

        assertThat(match).contains(new Match("near", 12.971, 77.591, 0.4));
    }

    @Test
    void findMatch_emptyWhenNoCandidates() {
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(List.of());

        assertThat(matchingService.findMatch(event())).isEmpty();
    }

    @Test
    void findMatch_emptyWhenFallbackReturnsNoDriversForFailedClient() {
        // With the circuit breaker fallback in place a failing location-service looks like an empty list.
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(null);

        assertThat(matchingService.findMatch(event())).isEmpty();
    }

    @Test
    void findMatch_propagatesClientFailureSoTheRecordIsRetried() {
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> matchingService.findMatch(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void findMatch_rejectsEventWithoutPickupCoordinates() {
        RideRequestedEvent bad = RideRequestedEvent.builder().rideId("ride-1").riderId("rider-1").build();

        assertThatThrownBy(() -> matchingService.findMatch(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ride-1");
    }

    private static RideRequestedEvent event() {
        return RideRequestedEvent.builder().rideId("ride-1").riderId("rider-1")
                .pickupLatitude(12.97).pickupLongitude(77.59).build();
    }

    private static NearbyDriverResponse driver(String id, double lat, double lon, double km) {
        return NearbyDriverResponse.builder().driverId(id).latitude(lat).longitude(lon).distanceInKm(km).build();
    }
}
