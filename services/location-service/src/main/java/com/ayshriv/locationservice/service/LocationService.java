package com.ayshriv.locationservice.service;

import com.ayshriv.locationservice.dto.DriverLocationRequest;
import com.ayshriv.locationservice.dto.NearbyDriverResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores driver positions in a Redis GEO set and a per-driver heartbeat key.
 * <p>
 * Redis GEO members cannot expire individually, so liveness is tracked by a separate
 * key with a TTL; drivers whose heartbeat has expired are treated as offline.
 */
@Slf4j
@Service
public class LocationService {

    static final String GEO_KEY = "drivers:locations";
    static final String HEARTBEAT_KEY_PREFIX = "driver:heartbeat:";

    /**
     * Stale drivers are dropped after the geo query, so ask Redis for more than
     * {@code limit} results to still be able to fill the page.
     */
    private static final int OVERSAMPLE_FACTOR = 3;

    private final StringRedisTemplate redisTemplate;
    private final Duration driverTtl;

    public LocationService(StringRedisTemplate redisTemplate,
                           @Value("${location.driver-ttl-seconds:30}") long driverTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.driverTtl = Duration.ofSeconds(driverTtlSeconds);
    }

    public void updateDriverLocation(DriverLocationRequest request) {
        // Redis GEO expects longitude first, then latitude.
        Point point = new Point(request.getLongitude(), request.getLatitude());
        redisTemplate.opsForGeo().add(GEO_KEY, point, request.getDriverId());
        redisTemplate.opsForValue().set(
                heartbeatKey(request.getDriverId()),
                String.valueOf(Instant.now().toEpochMilli()),
                driverTtl);
        log.debug("Updated location for driver {}", request.getDriverId());
    }

    /**
     * Returns online drivers within {@code radiusKm} of the point, nearest first.
     * Drivers with an expired heartbeat are skipped and lazily removed from the geo set.
     */
    public List<NearbyDriverResponse> findNearbyDrivers(double latitude, double longitude,
                                                        double radiusKm, int limit) {
        Circle area = new Circle(new Point(longitude, latitude), new Distance(radiusKm, Metrics.KILOMETERS));
        GeoRadiusCommandArgs args = GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeCoordinates()
                .includeDistance()
                .sortAscending()
                .limit((long) limit * OVERSAMPLE_FACTOR);

        GeoResults<GeoLocation<String>> results = redisTemplate.opsForGeo().radius(GEO_KEY, area, args);
        if (results == null || results.getContent().isEmpty()) {
            return List.of();
        }

        List<GeoResult<GeoLocation<String>>> candidates = results.getContent();
        List<String> heartbeatKeys = candidates.stream()
                .map(result -> heartbeatKey(result.getContent().getName()))
                .toList();
        List<String> heartbeats = redisTemplate.opsForValue().multiGet(heartbeatKeys);

        List<NearbyDriverResponse> online = new ArrayList<>();
        List<String> staleDriverIds = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            GeoLocation<String> location = candidates.get(i).getContent();
            boolean alive = heartbeats != null && heartbeats.get(i) != null;
            if (!alive) {
                staleDriverIds.add(location.getName());
            } else if (online.size() < limit) {
                online.add(NearbyDriverResponse.builder()
                        .driverId(location.getName())
                        .latitude(location.getPoint().getY())
                        .longitude(location.getPoint().getX())
                        .distanceInKm(candidates.get(i).getDistance().getValue())
                        .build());
            }
        }

        if (!staleDriverIds.isEmpty()) {
            redisTemplate.opsForGeo().remove(GEO_KEY, staleDriverIds.toArray(new String[0]));
            log.info("Removed {} stale driver(s) from geo set", staleDriverIds.size());
        }
        return online;
    }

    public void removeDriver(String driverId) {
        redisTemplate.opsForGeo().remove(GEO_KEY, driverId);
        redisTemplate.delete(heartbeatKey(driverId));
        log.info("Removed driver {}", driverId);
    }

    private String heartbeatKey(String driverId) {
        return HEARTBEAT_KEY_PREFIX + driverId;
    }
}
