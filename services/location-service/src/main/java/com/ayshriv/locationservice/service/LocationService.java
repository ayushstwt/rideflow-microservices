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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores driver positions in a Redis GEO set and a per-driver heartbeat key.
 * <p>
 * Redis GEO members cannot expire individually, so liveness is tracked by a separate
 * key with a TTL; drivers whose heartbeat has expired are treated as offline.
 * <p>
 * It also reserves drivers for rides so one driver is never matched to two rides: the reservation
 * keys are written and checked by Lua scripts, which Redis executes atomically.
 */
@Slf4j
@Service
public class LocationService {

    static final String GEO_KEY = "drivers:locations";
    static final String HEARTBEAT_KEY_PREFIX = "driver:heartbeat:";
    /** {@code driver:reservation:{driverId}} = rideId; present while the driver is busy. */
    static final String RESERVATION_KEY_PREFIX = "driver:reservation:";
    /** {@code ride:assignment:{rideId}} = driverId; makes reservation idempotent per ride. */
    static final String ASSIGNMENT_KEY_PREFIX = "ride:assignment:";

    static final String RESERVE_SCRIPT_PATH = "scripts/reserve_driver.lua";
    static final String RELEASE_SCRIPT_PATH = "scripts/release_driver.lua";

    private static final RedisScript<String> RESERVE_SCRIPT = loadScript(RESERVE_SCRIPT_PATH);
    private static final RedisScript<String> RELEASE_SCRIPT = loadScript(RELEASE_SCRIPT_PATH);

    /**
     * Stale drivers are dropped after the geo query, so ask Redis for more than
     * {@code limit} results to still be able to fill the page.
     */
    private static final int OVERSAMPLE_FACTOR = 3;

    private final StringRedisTemplate redisTemplate;
    private final Duration driverTtl;
    private final long reservationTtlSeconds;

    public LocationService(StringRedisTemplate redisTemplate,
                           @Value("${location.driver-ttl-seconds:30}") long driverTtlSeconds,
                           @Value("${location.reservation-ttl-seconds:1800}") long reservationTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.driverTtl = Duration.ofSeconds(driverTtlSeconds);
        this.reservationTtlSeconds = reservationTtlSeconds;
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
     * Returns online, unreserved drivers within {@code radiusKm} of the point, nearest first.
     * Drivers with an expired heartbeat are skipped and lazily removed from the geo set;
     * drivers reserved for a ride are busy and skipped.
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
        List<String> reservationKeys = candidates.stream()
                .map(result -> reservationKey(result.getContent().getName()))
                .toList();
        List<String> heartbeats = redisTemplate.opsForValue().multiGet(heartbeatKeys);
        List<String> reservations = redisTemplate.opsForValue().multiGet(reservationKeys);

        List<NearbyDriverResponse> online = new ArrayList<>();
        List<String> staleDriverIds = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            GeoLocation<String> location = candidates.get(i).getContent();
            boolean alive = heartbeats != null && heartbeats.get(i) != null;
            boolean busy = reservations != null && reservations.get(i) != null;
            if (!alive) {
                staleDriverIds.add(location.getName());
            } else if (!busy && online.size() < limit) {
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

    /**
     * Reserves the first candidate (in the given order) that is not already reserved, in one atomic Lua
     * script. If the ride already holds a driver, that same driver is returned, so processing a request
     * twice never reserves a second driver. Empty when every candidate is taken.
     */
    public Optional<String> reserveFirstAvailable(String rideId, List<String> orderedCandidateDriverIds) {
        List<Object> args = new ArrayList<>();
        args.add(rideId);
        args.add(String.valueOf(reservationTtlSeconds));
        args.add(RESERVATION_KEY_PREFIX);
        args.addAll(orderedCandidateDriverIds);

        String driverId = redisTemplate.execute(RESERVE_SCRIPT, List.of(assignmentKey(rideId)), args.toArray());
        log.info("Ride {}: reservation among {} candidate(s) -> {}", rideId, orderedCandidateDriverIds.size(),
                driverId == null ? "none free" : driverId);
        return Optional.ofNullable(driverId);
    }

    /** Frees the driver held for the ride. Idempotent: unknown or already released rides are a no-op. */
    public void releaseDriver(String rideId) {
        String driverId = redisTemplate.execute(RELEASE_SCRIPT, List.of(assignmentKey(rideId)),
                rideId, RESERVATION_KEY_PREFIX);
        if (driverId == null) {
            log.debug("Ride {}: nothing to release", rideId);
        } else {
            log.info("Ride {}: released driver {}", rideId, driverId);
        }
    }

    private static RedisScript<String> loadScript(String path) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(String.class);
        return script;
    }

    private String heartbeatKey(String driverId) {
        return HEARTBEAT_KEY_PREFIX + driverId;
    }

    private String reservationKey(String driverId) {
        return RESERVATION_KEY_PREFIX + driverId;
    }

    private String assignmentKey(String rideId) {
        return ASSIGNMENT_KEY_PREFIX + rideId;
    }
}
