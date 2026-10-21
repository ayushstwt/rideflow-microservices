# matching-service

Port `8084`. Event-driven only: it has no REST API of its own (just the actuator `health` and `info` endpoints). It consumes `ride.requested`, asks location-service for nearby drivers, picks the best one and publishes the result.

## Flow

```mermaid
sequenceDiagram
    actor Rider
    participant RS as ride-service
    participant K as Kafka
    participant MS as matching-service
    participant LS as location-service

    Rider->>RS: POST /api/v1/rides/request
    RS->>RS: save ride (REQUESTED -> MATCHING), commit
    RS->>K: ride.requested (key = rideId)
    K->>MS: ride.requested
    MS->>LS: GET /api/v1/locations/drivers/nearby
    LS-->>MS: nearby drivers (or empty / fallback)
    MS->>MS: score and rank candidates
    alt driver found
        MS->>K: ride.matched (key = rideId)
    else none found
        MS->>K: ride.match-failed (key = rideId)
    end
```

## Consumed: `ride.requested`

Group `matching-service-group`, offsets committed after each record.

```json
{
  "rideId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "riderId": "rider-42",
  "pickupLatitude": 12.9716,
  "pickupLongitude": 77.5946,
  "pickupAddress": "MG Road",
  "dropLatitude": 13.0827,
  "dropLongitude": 80.2707,
  "dropAddress": "T Nagar",
  "requestedAt": "2026-01-01T10:00:00Z"
}
```

## Produced

Both topics use `rideId` as the message key.

### `ride.matched`

```json
{
  "rideId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "riderId": "rider-42",
  "driverId": "driver-7",
  "driverLatitude": 12.9741,
  "driverLongitude": 77.5968,
  "distanceToPickupKm": 0.42,
  "matchedAt": "2026-01-01T10:00:01Z"
}
```

### `ride.match-failed`

```json
{
  "rideId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "riderId": "rider-42",
  "reason": "NO_DRIVERS_AVAILABLE",
  "failedAt": "2026-01-01T10:00:01Z"
}
```

## Scoring

```
score = wDistance * (1 - min(distanceKm / radiusKm, 1)) + wRating * (rating / 5.0)
```

| Setting | Default |
|---------|---------|
| `matching.search-radius-km` | `5.0` |
| `matching.max-candidates` | `10` |
| `matching.weights.distance` | `0.7` |
| `matching.weights.rating` | `0.3` |

The two weights must sum to `1.0`, otherwise the service refuses to start. The highest score wins; ties go to the smaller distance, then to the alphabetically smaller `driverId`, so the same input always gives the same driver.

Ratings come from `DriverRatingProvider`. Until a driver-service exists, `StaticDriverRatingProvider` returns `4.5` for every driver, so ranking is effectively by distance.

## Failure handling

- **location-service down or slow:** 2s connect and read timeouts, behind a Resilience4j circuit breaker. The fallback logs a warning and returns no drivers, so the ride is reported as `NO_DRIVERS_AVAILABLE`.
- **Processing error:** the record is retried 3 times, 1s apart, then published to `ride.requested.DLT` (3 partitions).
- **Undeserializable message:** goes straight to `ride.requested.DLT` without retries.
- **Publish failure:** logged with the `rideId`; there is no retry yet (Step 8).
