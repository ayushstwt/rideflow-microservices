package com.ayshriv.locationservice;

import com.ayshriv.events.RideClosedEvent;
import com.ayshriv.locationservice.config.RedisConfig;
import com.ayshriv.locationservice.dto.DriverLocationRequest;
import com.ayshriv.locationservice.dto.NearbyDriverResponse;
import com.ayshriv.locationservice.messaging.RideClosedListener;
import com.ayshriv.locationservice.service.LocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the reservation Lua scripts against a REAL Redis. The unit tests mock Redis and cannot prove atomicity.
 * Needs Docker; skipped (not failed) without it. Run with {@code mvn verify}.
 * <p>
 * Only Redis is wired (no Kafka): the listener is called directly.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = LocationRedisIT.RedisOnlyConfig.class,
        properties = {
                "location.driver-ttl-seconds=2",       // short heartbeat so expiry can be observed
                "location.reservation-ttl-seconds=60"
        })
class LocationRedisIT {

    private static final double LAT = 12.9716;
    private static final double LON = 77.5946;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Configuration
    @ImportAutoConfiguration(RedisAutoConfiguration.class)
    @Import({RedisConfig.class, LocationService.class, RideClosedListener.class})
    static class RedisOnlyConfig {
    }

    @Autowired
    private LocationService locationService;
    @Autowired
    private RideClosedListener rideClosedListener;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    // a) ----------------------------------------------------------------------------------------------------

    @Test
    void twentyRidesRaceForOneDriver_exactlyOneWins() {
        int rides = 20;
        ExecutorService pool = Executors.newFixedThreadPool(rides);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        List<CompletableFuture<Optional<String>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < rides; i++) {
                String rideId = "ride-" + i;
                results.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        return locationService.reserveFirstAvailable(rideId, List.of("driver-1"));
                    } finally {
                        finished.incrementAndGet();
                    }
                }, pool));
            }
            startGate.countDown();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(finished.get()).isEqualTo(rides));
        } finally {
            pool.shutdownNow();
        }

        List<Optional<String>> winners = results.stream().map(CompletableFuture::join).filter(Optional::isPresent).toList();
        assertThat(winners).hasSize(1);
        assertThat(winners.get(0)).contains("driver-1");
        assertThat(redis.opsForValue().get("driver:reservation:driver-1")).startsWith("ride-");
    }

    @Test
    void manyRidesRaceForSeveralDrivers_noDriverIsHandedOutTwice() {
        int rides = 20;
        List<String> candidates = IntStream.range(0, 5).mapToObj(i -> "driver-" + i).toList();
        ExecutorService pool = Executors.newFixedThreadPool(rides);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        List<CompletableFuture<Optional<String>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < rides; i++) {
                String rideId = "ride-" + i;
                results.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        return locationService.reserveFirstAvailable(rideId, candidates);
                    } finally {
                        finished.incrementAndGet();
                    }
                }, pool));
            }
            startGate.countDown();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(finished.get()).isEqualTo(rides));
        } finally {
            pool.shutdownNow();
        }

        List<String> reserved = results.stream().map(CompletableFuture::join).flatMap(Optional::stream).toList();
        assertThat(reserved).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(candidates);
    }

    // b) ----------------------------------------------------------------------------------------------------

    @Test
    void reservingTwiceForTheSameRide_returnsTheSameDriverEvenWithOtherCandidates() {
        Optional<String> first = locationService.reserveFirstAvailable("ride-1", List.of("driver-1", "driver-2"));
        Optional<String> again = locationService.reserveFirstAvailable("ride-1", List.of("driver-2", "driver-1"));

        assertThat(first).contains("driver-1");
        assertThat(again).contains("driver-1");
        assertThat(redis.hasKey("driver:reservation:driver-2")).isFalse();
    }

    @Test
    void secondRideSkipsTheTakenDriverAndTakesTheNextCandidateInOrder() {
        locationService.reserveFirstAvailable("ride-1", List.of("driver-1"));

        Optional<String> second = locationService.reserveFirstAvailable("ride-2", List.of("driver-1", "driver-2", "driver-3"));

        assertThat(second).contains("driver-2");
    }

    @Test
    void reservationIsEmptyWhenEveryCandidateIsTaken() {
        locationService.reserveFirstAvailable("ride-1", List.of("driver-1"));

        assertThat(locationService.reserveFirstAvailable("ride-2", List.of("driver-1"))).isEmpty();
        assertThat(redis.hasKey("ride:assignment:ride-2")).isFalse();
    }

    @Test
    void reservationKeysCarryTheConfiguredSafetyNetTtl() {
        locationService.reserveFirstAvailable("ride-1", List.of("driver-1"));

        assertThat(redis.getExpire("driver:reservation:driver-1")).isBetween(1L, 60L);
        assertThat(redis.getExpire("ride:assignment:ride-1")).isBetween(1L, 60L);
    }

    // c) ----------------------------------------------------------------------------------------------------

    @Test
    void releaseFreesTheDriverAndCallingItTwiceIsHarmless() {
        locationService.reserveFirstAvailable("ride-1", List.of("driver-1"));
        assertThat(locationService.reserveFirstAvailable("ride-2", List.of("driver-1"))).isEmpty();

        locationService.releaseDriver("ride-1");
        locationService.releaseDriver("ride-1");

        assertThat(redis.hasKey("driver:reservation:driver-1")).isFalse();
        assertThat(redis.hasKey("ride:assignment:ride-1")).isFalse();
        assertThat(locationService.reserveFirstAvailable("ride-2", List.of("driver-1"))).contains("driver-1");
    }

    @Test
    void releasingAnUnknownRideDoesNotFreeSomeoneElsesReservation() {
        locationService.reserveFirstAvailable("ride-1", List.of("driver-1"));

        locationService.releaseDriver("ride-unknown");

        assertThat(redis.opsForValue().get("driver:reservation:driver-1")).isEqualTo("ride-1");
        assertThat(locationService.reserveFirstAvailable("ride-2", List.of("driver-1"))).isEmpty();
    }

    @Test
    void releaseNeverDeletesAReservationThatNowBelongsToAnotherRide() {
        // ride-1 held driver-1, its reservation expired, and ride-2 got the driver in the meantime.
        locationService.reserveFirstAvailable("ride-1", List.of("driver-1"));
        redis.opsForValue().set("driver:reservation:driver-1", "ride-2");

        locationService.releaseDriver("ride-1");

        assertThat(redis.hasKey("ride:assignment:ride-1")).isFalse();
        assertThat(redis.opsForValue().get("driver:reservation:driver-1")).isEqualTo("ride-2");
    }

    // d) ----------------------------------------------------------------------------------------------------

    @Test
    void nearbyExcludesReservedDriversAndDriversWhoseHeartbeatExpired() {
        locationService.updateDriverLocation(new DriverLocationRequest("stale", LAT, LON));
        await().atMost(Duration.ofSeconds(10)).until(() -> !redis.hasKey("driver:heartbeat:stale"));
        locationService.updateDriverLocation(new DriverLocationRequest("busy", LAT, LON));
        locationService.updateDriverLocation(new DriverLocationRequest("free", LAT, LON));
        locationService.reserveFirstAvailable("ride-1", List.of("busy"));

        List<NearbyDriverResponse> nearby = locationService.findNearbyDrivers(LAT, LON, 5.0, 10);

        assertThat(nearby).extracting(NearbyDriverResponse::getDriverId).containsExactly("free");
    }

    @Test
    void releasedDriverIsReturnedByNearbyAgain() {
        locationService.updateDriverLocation(new DriverLocationRequest("driver-1", LAT, LON));
        locationService.reserveFirstAvailable("ride-1", List.of("driver-1"));
        assertThat(locationService.findNearbyDrivers(LAT, LON, 5.0, 10)).isEmpty();

        locationService.releaseDriver("ride-1");

        assertThat(locationService.findNearbyDrivers(LAT, LON, 5.0, 10))
                .extracting(NearbyDriverResponse::getDriverId).containsExactly("driver-1");
    }

    // e) ----------------------------------------------------------------------------------------------------

    @Test
    void rideClosedListener_releasesTheDriver() {
        locationService.reserveFirstAvailable("ride-1", List.of("driver-1"));

        rideClosedListener.onRideClosed(RideClosedEvent.builder()
                .rideId("ride-1").riderId("rider-1").driverId("driver-1").finalStatus("COMPLETED").build());

        assertThat(redis.hasKey("driver:reservation:driver-1")).isFalse();
        assertThat(redis.hasKey("ride:assignment:ride-1")).isFalse();
    }

    @Test
    void rideClosedListener_isHarmlessForAnUnknownRideAndForRedeliveries() {
        RideClosedEvent event = RideClosedEvent.builder().rideId("never-reserved").riderId("rider-1")
                .finalStatus("NO_DRIVER_FOUND").build();

        rideClosedListener.onRideClosed(event);
        rideClosedListener.onRideClosed(event);

        assertThat(redis.keys("*")).isEmpty();
    }
}
