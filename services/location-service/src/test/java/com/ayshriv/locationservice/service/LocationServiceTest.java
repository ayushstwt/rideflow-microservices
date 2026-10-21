package com.ayshriv.locationservice.service;

import com.ayshriv.locationservice.dto.DriverLocationRequest;
import com.ayshriv.locationservice.dto.NearbyDriverResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    private static final long TTL_SECONDS = 30;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private GeoOperations<String, String> geoOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private LocationService locationService;

    @BeforeEach
    void setUp() {
        locationService = new LocationService(redisTemplate, TTL_SECONDS);
    }

    @Test
    void updateDriverLocation_addsGeoPointLongitudeFirstAndSetsHeartbeatWithTtl() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        locationService.updateDriverLocation(new DriverLocationRequest("d1", 12.5, 77.5));

        ArgumentCaptor<Point> point = ArgumentCaptor.forClass(Point.class);
        verify(geoOperations).add(eq("drivers:locations"), point.capture(), eq("d1"));
        assertThat(point.getValue().getX()).isEqualTo(77.5);
        assertThat(point.getValue().getY()).isEqualTo(12.5);
        verify(valueOperations).set(eq("driver:heartbeat:d1"), anyString(), eq(Duration.ofSeconds(TTL_SECONDS)));
    }

    @Test
    void findNearbyDrivers_filtersStaleDriversAndRemovesThemFromGeoSet() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(geoOperations.radius(eq("drivers:locations"), any(Circle.class), any(GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(result("d1", 1.0), result("d2", 2.0), result("d3", 3.0)));
        when(valueOperations.multiGet(List.of("driver:heartbeat:d1", "driver:heartbeat:d2", "driver:heartbeat:d3")))
                .thenReturn(Arrays.asList("1", null, "1"));

        List<NearbyDriverResponse> drivers = locationService.findNearbyDrivers(12.0, 77.0, 5.0, 10);

        assertThat(drivers).extracting(NearbyDriverResponse::getDriverId).containsExactly("d1", "d3");
        assertThat(drivers.get(0).getDistanceInKm()).isEqualTo(1.0);
        verify(geoOperations).remove("drivers:locations", "d2");
    }

    @Test
    void findNearbyDrivers_returnsAtMostLimitAndKeepsDistanceOrder() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(geoOperations.radius(eq("drivers:locations"), any(Circle.class), any(GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults(result("d1", 1.0), result("d2", 2.0), result("d3", 3.0)));
        when(valueOperations.multiGet(any())).thenReturn(List.of("1", "1", "1"));

        List<NearbyDriverResponse> drivers = locationService.findNearbyDrivers(12.0, 77.0, 5.0, 2);

        assertThat(drivers).extracting(NearbyDriverResponse::getDriverId).containsExactly("d1", "d2");
        verify(geoOperations, never()).remove(anyString(), any(String[].class));
    }

    @Test
    void findNearbyDrivers_returnsEmptyWhenNothingInRadius() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.radius(eq("drivers:locations"), any(Circle.class), any(GeoRadiusCommandArgs.class)))
                .thenReturn(geoResults());

        assertThat(locationService.findNearbyDrivers(12.0, 77.0, 5.0, 10)).isEmpty();
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void removeDriver_removesFromGeoSetAndDeletesHeartbeat() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);

        locationService.removeDriver("d1");

        verify(geoOperations).remove("drivers:locations", "d1");
        verify(redisTemplate).delete("driver:heartbeat:d1");
    }

    @SafeVarargs
    private static GeoResults<GeoLocation<String>> geoResults(GeoResult<GeoLocation<String>>... results) {
        return new GeoResults<>(List.of(results));
    }

    private static GeoResult<GeoLocation<String>> result(String driverId, double distanceKm) {
        return new GeoResult<>(new GeoLocation<>(driverId, new Point(77.0, 12.0)),
                new Distance(distanceKm, Metrics.KILOMETERS));
    }
}
