package com.ayshriv.matchingservice.service;

import com.ayshriv.events.RideRequestedEvent;
import com.ayshriv.matchingservice.client.LocationServiceClient;
import com.ayshriv.matchingservice.config.MatchingProperties;
import com.ayshriv.matchingservice.dto.NearbyDriverResponse;
import com.ayshriv.matchingservice.dto.ReserveRequest;
import com.ayshriv.matchingservice.dto.ReserveResponse;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void findMatch_reservesAndReturnsBestScoredDriver() {
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(List.of(
                driver("far", 12.99, 77.60, 3.0), driver("near", 12.971, 77.591, 0.4)));
        when(locationClient.reserve(any())).thenReturn(reserved("near"));

        Optional<Match> match = matchingService.findMatch(event());

        assertThat(match).contains(new Match("near", 12.971, 77.591, 0.4));
    }

    @Test
    void findMatch_sendsCandidatesToReserveBestFirst() {
        // Deliberately not in score order: scoring must decide the order, not the nearby-endpoint order.
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(List.of(
                driver("mid", 12.98, 77.60, 2.0), driver("far", 12.99, 77.61, 4.0), driver("near", 12.971, 77.591, 0.4)));
        when(locationClient.reserve(any())).thenReturn(reserved("near"));

        matchingService.findMatch(event());

        ArgumentCaptor<ReserveRequest> request = ArgumentCaptor.forClass(ReserveRequest.class);
        verify(locationClient).reserve(request.capture());
        assertThat(request.getValue().getRideId()).isEqualTo("ride-1");
        assertThat(request.getValue().getCandidateDriverIds()).containsExactly("near", "mid", "far");
    }

    @Test
    void findMatch_usesSecondDriverWhenTopDriverWasTakenMeanwhile() {
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(List.of(
                driver("top", 12.971, 77.591, 0.4), driver("second", 12.98, 77.60, 1.5)));
        // location-service skipped "top" because another ride reserved it between search and reserve.
        when(locationClient.reserve(any())).thenReturn(reserved("second"));

        Optional<Match> match = matchingService.findMatch(event());

        assertThat(match).contains(new Match("second", 12.98, 77.60, 1.5));
        ArgumentCaptor<ReserveRequest> request = ArgumentCaptor.forClass(ReserveRequest.class);
        verify(locationClient).reserve(request.capture());
        assertThat(request.getValue().getCandidateDriverIds()).containsExactly("top", "second");
    }

    @Test
    void findMatch_emptyWhenReserveRespondsConflict() {
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(List.of(
                driver("d1", 12.971, 77.591, 0.4)));
        when(locationClient.reserve(any())).thenThrow(conflict());

        assertThat(matchingService.findMatch(event())).isEmpty();
    }

    @Test
    void findMatch_emptyWhenReserveFallbackIsHit() {
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(List.of(
                driver("d1", 12.971, 77.591, 0.4)));
        when(locationClient.reserve(any())).thenReturn(Optional.empty());

        assertThat(matchingService.findMatch(event())).isEmpty();
    }

    @Test
    void findMatch_sameRideProcessedTwiceYieldsTheSameDriver() {
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(List.of(
                driver("d1", 12.971, 77.591, 0.4), driver("d2", 12.98, 77.60, 1.5)));
        // location-service (Lua script) returns the ride's existing assignment on the second call.
        when(locationClient.reserve(any())).thenReturn(reserved("d1"), reserved("d1"));

        Optional<Match> first = matchingService.findMatch(event());
        Optional<Match> second = matchingService.findMatch(event());

        assertThat(first).isPresent();
        assertThat(second).isEqualTo(first);
        verify(locationClient, times(2)).reserve(any());
    }

    @Test
    void findMatch_redeliveryStillMatchesWhenOwnDriverIsHiddenFromNearbyResults() {
        // On redelivery the ride's own driver is reserved, so nearby no longer lists them; the reservation
        // still answers with the same driver, whose position is then unknown.
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(List.of(
                driver("d2", 12.98, 77.60, 1.5)));
        when(locationClient.reserve(any())).thenReturn(reserved("d1"));

        assertThat(matchingService.findMatch(event())).contains(new Match("d1", null, null, null));
    }

    @Test
    void findMatch_sendsAtMost20CandidatesToReserve() {
        List<NearbyDriverResponse> thirty = IntStream.range(0, 30)
                .mapToObj(i -> driver("d%02d".formatted(i), 12.97, 77.59, 0.1 + i * 0.1))
                .toList();
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(thirty);
        when(locationClient.reserve(any())).thenReturn(reserved("d00"));

        matchingService.findMatch(event());

        ArgumentCaptor<ReserveRequest> request = ArgumentCaptor.forClass(ReserveRequest.class);
        verify(locationClient).reserve(request.capture());
        assertThat(request.getValue().getCandidateDriverIds()).hasSize(20).startsWith("d00", "d01");
    }

    @Test
    void findMatch_emptyWhenNoCandidatesAndNothingIsReserved() {
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(List.of());

        assertThat(matchingService.findMatch(event())).isEmpty();
        verify(locationClient, never()).reserve(any());
    }

    @Test
    void findMatch_emptyWhenFallbackReturnsNoDriversForFailedClient() {
        // With the circuit breaker fallback in place a failing location-service looks like an empty list.
        when(locationClient.findNearbyDrivers(12.97, 77.59, 5.0, 10)).thenReturn(null);

        assertThat(matchingService.findMatch(event())).isEmpty();
        verify(locationClient, never()).reserve(any());
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

    private static Optional<ReserveResponse> reserved(String driverId) {
        return Optional.of(new ReserveResponse(driverId));
    }

    private static FeignException.Conflict conflict() {
        Request request = Request.create(Request.HttpMethod.POST, "http://location-service/reserve",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return new FeignException.Conflict("409 NO_DRIVER_AVAILABLE", request, null, Map.of());
    }
}
