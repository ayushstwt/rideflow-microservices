# Testing

## Test pyramid

```
            /\
           /  \     Smoke test (scripts/smoke-test.sh)   real stack, run by CI in Step 11
          /----\
         / *IT  \   Needs Docker (Testcontainers + real Redis)         mvn verify
        /--------\
       / in-JVM   \ Real Spring context + H2, EmbeddedKafka, WireMock  mvn test
      /  *Test     \
     /--------------\
    /  unit *Test    \ Plain JUnit/Mockito, no Spring context          mvn test
   /------------------\
```

Most tests are fast unit tests. A smaller layer starts the real Spring context of one service and replaces its
neighbours with in-process stand-ins (H2, an embedded Kafka broker, WireMock). The top layers need real infrastructure and are
kept few.

## Naming rule

| Suffix | What it is | Runs with | Plugin | Docker |
|--------|------------|-----------|--------|--------|
| `*Test` | unit test, or in-JVM integration test (H2, EmbeddedKafka, WireMock) | `mvn test` | Surefire | not needed |
| `*IT` | test that needs Docker / Testcontainers | `mvn verify` | Failsafe | needed |

- Surefire runs only `**/*Test.java` and excludes `**/*IT.java`; Failsafe runs only `**/*IT.java`. The plugins are configured in the
  parent `pom.xml` (`pluginManagement`) and enabled for every module.
- Every `*IT` class must carry `@Testcontainers(disabledWithoutDocker = true)`. Without Docker the class is **skipped**, not failed, so
  `mvn verify` still works on a machine without Docker.
- Asynchronous assertions use Awaitility (`await().atMost(...).untilAsserted(...)`). `Thread.sleep` is not used in tests.
- Tests must not depend on each other or on the order they run in. Integration tests use their own rider / ride ids and filter Kafka
  records by `rideId`.

## How to run

```bash
mvn test                                  # everything that needs no Docker (all modules)
mvn verify                                # *Test, then the *IT tests (needs Docker) and the JaCoCo reports
mvn test -pl services/ride-service -am    # one module (and the modules it depends on)
mvn verify -pl services/location-service -am
mvn test -pl services/matching-service -am -Dtest=MatchingFlowIntegrationTest   # one class
```

`-am` ("also make") builds `libs/common-events` first; the services depend on it.

### Coverage

JaCoCo is attached to the build: `mvn verify` writes `target/site/jacoco/index.html` in every module. Reports only, no minimum coverage is
enforced yet.

### Smoke test

`scripts/smoke-test.sh` checks a **running** stack (needs `curl` and `jq`). It is meant for CI and is not part of `mvn`:

```bash
LOCATION_URL=http://localhost:8082 RIDE_URL=http://localhost:8083 MATCHING_URL=http://localhost:8084 scripts/smoke-test.sh
```

It waits for the three `/actuator/health` endpoints, registers 3 drivers, requests a ride with an `Idempotency-Key`, waits for
`ACCEPTED`, checks that the assigned driver is hidden from the nearby search, cancels the ride and checks that the driver comes back.
It prints `PASS:` / `FAIL:` lines and exits non-zero on failure.

## What each test proves

### libs/common-events

| Test | Proves |
|------|--------|
| `EventSerializationTest` | `RideRequestedEvent`, `RideMatchedEvent`, `RideMatchFailedEvent` and `RideClosedEvent` survive a JSON round trip with a Spring-Boot-style `ObjectMapper`; `Instant`s are ISO-8601 strings; unknown JSON properties do not break reading (so services can be upgraded one by one); a payload without `attempt` reads as `attempt = 1`; topic names are stable. This is the contract between the services. |

### services/ride-service

| Test | Kind | Proves |
|------|------|--------|
| `RideRepositoryTest` | `@DataJpaTest`, H2 | The unique `(rider_id, idempotency_key)` constraint really rejects a duplicate (and allows other riders / null keys); the active-ride query finds exactly the five active statuses; the scheduler query returns only old `MATCHING` rides, oldest first, and honours the page size. |
| `RideFlowIntegrationTest` | `@SpringBootTest`, H2, EmbeddedKafka, real scheduler | (a) a ride request produces exactly one `ride.requested`, keyed by `rideId`, `attempt = 1`; (b) `ride.matched` makes the ride `ACCEPTED` with the driver and a duplicate changes nothing; (c) `ride.match-failed` keeps the ride `MATCHING`, the scheduler republishes with `attempt = 2` and `3`, and after the last attempt the ride is `NO_DRIVER_FOUND` with one `ride.closed`; a lost result is recovered by the scheduler alone; (d) cancelling an `ACCEPTED` ride publishes `ride.closed` with the driver id; (e) the same `Idempotency-Key` twice gives the same ride and one `ride.requested`; (f) a transaction that fails right before commit leaves no ride and publishes nothing. |
| `RideServiceReliabilityTest`, `StuckRideRetrySchedulerTest`, `RideClosedEventListenerTest`, ... | unit | Retry policy, scheduler batching and lost-race handling, `AFTER_COMMIT` publishing of `ride.closed`, exactly-once closed event per terminal transition. |

### services/matching-service

`MatchingFlowIntegrationTest` (`@SpringBootTest`, EmbeddedKafka, WireMock as location-service; real Feign client and circuit breaker):

| Scenario | Proves |
|----------|--------|
| 3 nearby drivers | `reserve` receives the candidates best-first (not in the order location-service returned them) and the reserved driver is the one in `ride.matched`, with that driver's position and distance. |
| top driver taken | The driver returned by the reservation is used, even if it was not the top-scored one. |
| no nearby drivers | `ride.match-failed` with `NO_DRIVERS_AVAILABLE`, and no reserve call. |
| reserve answers 409 | `ride.match-failed`. |
| location-service 500 / timeout (nearby and reserve) | The circuit-breaker fallback turns it into `ride.match-failed`; nothing reaches the dead-letter topic. |
| malformed JSON | The record lands on `ride.requested.DLT` and the next valid message is still matched. |

### services/location-service

`LocationRedisIT` (Testcontainers, real `redis:7-alpine`, Docker required). The unit tests mock Redis, so only this test can prove the
Lua scripts:

| Test | Proves |
|------|--------|
| 20 threads, one driver | Exactly one ride gets the driver (atomic reservation). |
| 20 threads, 5 drivers | Every driver is handed out at most once. |
| same ride twice | The same driver is returned (idempotent), whatever the candidate order. |
| release | Frees the driver; calling it twice is harmless; an unknown ride frees nobody; a reservation that now belongs to another ride is never deleted. |
| nearby | Excludes reserved drivers and drivers whose heartbeat expired (heartbeat TTL set to 2s for the test), and returns a released driver again. |
| `RideClosedListener` | Calling the listener releases the driver (no Kafka needed). |

Other location-service tests (`LocationServiceTest`, `LocationControllerReservationTest`, `RideClosedListenerTest`) are mock-based and check
the Redis keys, arguments and TTLs passed to the scripts, the validation rules and the HTTP status codes.

## What is not covered yet

- The Lua scripts, ride-service against MySQL, and the full stack in Docker are only exercised by `*IT` tests and the smoke test, which
  CI will run in Step 11. Nothing runs them in a plain `mvn test`.
- There is no coverage threshold.
