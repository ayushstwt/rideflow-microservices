# ride-service API

Base URL: `http://localhost:8083/api/v1/rides`

Ride ids are UUID strings. Fares are decimals with two places: `50 + 12 per km` (straight-line distance),
minimum `60.00`.

## Status lifecycle

```mermaid
stateDiagram-v2
    [*] --> REQUESTED
    REQUESTED --> MATCHING
    REQUESTED --> CANCELLED
    MATCHING --> ACCEPTED
    MATCHING --> NO_DRIVER_FOUND
    MATCHING --> CANCELLED
    ACCEPTED --> DRIVER_ARRIVING
    ACCEPTED --> RIDE_STARTED
    ACCEPTED --> CANCELLED
    DRIVER_ARRIVING --> RIDE_STARTED
    DRIVER_ARRIVING --> CANCELLED
    RIDE_STARTED --> COMPLETED
    COMPLETED --> [*]
    CANCELLED --> [*]
    NO_DRIVER_FOUND --> [*]
```

`COMPLETED`, `CANCELLED` and `NO_DRIVER_FOUND` are terminal. A new ride is saved as `REQUESTED` and moved to
`MATCHING` in the same transaction. `ACCEPTED` and `NO_DRIVER_FOUND` are set by the matching flow (added in a
later step), not by an endpoint. Any disallowed move returns `409 Conflict`.

## POST /request

Create a ride. Returns `201 Created`.

```bash
curl -X POST http://localhost:8083/api/v1/rides/request \
  -H "Content-Type: application/json" \
  -d '{
    "riderId": "rider-1",
    "pickupLatitude": 12.9716, "pickupLongitude": 77.5946, "pickupAddress": "MG Road",
    "dropLatitude": 13.1986, "dropLongitude": 77.7066, "dropAddress": "Airport"
  }'
```

```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "riderId": "rider-1",
  "driverId": null,
  "pickupLatitude": 12.9716,
  "pickupLongitude": 77.5946,
  "pickupAddress": "MG Road",
  "dropLatitude": 13.1986,
  "dropLongitude": 77.7066,
  "dropAddress": "Airport",
  "status": "MATCHING",
  "estimatedFare": 386.06,
  "actualFare": null,
  "createdAt": "2026-01-01T10:00:00.123Z",
  "updatedAt": "2026-01-01T10:00:00.150Z",
  "startedAt": null,
  "completedAt": null,
  "version": 1
}
```

Errors: `400` with a field -> message map (invalid or missing fields), or `{"error": "..."}` when pickup and
drop are identical.

```json
{"riderId": "riderId must not be blank", "pickupLatitude": "pickupLatitude must be <= 90"}
```

## GET /{rideId}

```bash
curl http://localhost:8083/api/v1/rides/7c9e6679-7425-40de-944b-e07fc1f90ae7
```

`200` with the ride (same shape as above), or `404`:

```json
{"error": "Ride not found: 7c9e6679-7425-40de-944b-e07fc1f90ae7"}
```

## GET /{rideId}/status

Lightweight view for rider apps that poll: only the id, status and assigned driver (`null` until matched). `404` for an unknown ride.

```bash
curl http://localhost:8083/api/v1/rides/7c9e6679-7425-40de-944b-e07fc1f90ae7/status
```

```json
{ "rideId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "status": "ACCEPTED", "driverId": "driver-7" }
```

## GET /rider/{riderId}

A rider's rides, newest first. `200` with a JSON array (empty if none).

```bash
curl http://localhost:8083/api/v1/rides/rider/rider-1
```

## PUT /{rideId}/start

Requires an assigned driver and status `ACCEPTED` or `DRIVER_ARRIVING`. Sets `startedAt`.

```bash
curl -X PUT http://localhost:8083/api/v1/rides/7c9e6679-7425-40de-944b-e07fc1f90ae7/start
```

## PUT /{rideId}/complete

Requires status `RIDE_STARTED`. Sets `completedAt` and `actualFare` (equal to `estimatedFare`).

```bash
curl -X PUT http://localhost:8083/api/v1/rides/7c9e6679-7425-40de-944b-e07fc1f90ae7/complete
```

## PUT /{rideId}/cancel

Allowed from `REQUESTED`, `MATCHING`, `ACCEPTED` and `DRIVER_ARRIVING`.

```bash
curl -X PUT http://localhost:8083/api/v1/rides/7c9e6679-7425-40de-944b-e07fc1f90ae7/cancel
```

## Errors

| Status | When |
|--------|------|
| 400 | Validation failure, malformed body, identical pickup/drop |
| 404 | Unknown ride id |
| 409 | Disallowed status transition, starting without a driver, or a concurrent update (optimistic lock) |
| 500 | Unexpected failure; body is `{"error": "Internal server error"}` with no stack trace |

State-change endpoints return `409` with `{"error": "Ride <id> cannot move from CANCELLED to RIDE_STARTED"}`.

## Kafka events

ride-service publishes to Kafka only **after** the database transaction commits (`@TransactionalEventListener(AFTER_COMMIT)`). A rolled-back request sends nothing.

### ride.requested

| | |
|---|---|
| Topic | `ride.requested` (3 partitions, replication factor 1) |
| Key | `rideId` (keeps all events of one ride ordered in one partition) |
| Producer | ride-service, with `acks=all` and idempotence enabled |
| Consumer | matching-service |

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

The topics `ride.matched` and `ride.match-failed` are also declared at startup; they are produced by matching-service.

### Consumed topics

Group `ride-service-group`, offsets committed after each record. Each topic has its own deserializer type.

| Topic | Payload | Effect |
|-------|---------|--------|
| `ride.matched` | `RideMatchedEvent` (`rideId`, `riderId`, `driverId`, `driverLatitude`, `driverLongitude`, `distanceToPickupKm`, `matchedAt`) | `MATCHING` -> `ACCEPTED`, sets `driverId` |
| `ride.match-failed` | `RideMatchFailedEvent` (`rideId`, `riderId`, `reason`, `failedAt`) | `MATCHING` -> `NO_DRIVER_FOUND` |

```json
{ "rideId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "riderId": "rider-42", "driverId": "driver-7",
  "driverLatitude": 12.9741, "driverLongitude": 77.5968, "distanceToPickupKm": 0.42,
  "matchedAt": "2026-01-01T10:00:01Z" }
```

### Why this is safe

- **Idempotent:** a result is applied only while the ride is `MATCHING`. A duplicate, a late message after the rider cancelled, or a message for an unknown ride is logged and ignored. No exception is thrown, so Kafka does not redeliver it.
- **Ordering:** the `ride.requested` event is sent only after the ride is committed as `MATCHING`, so a result can never arrive for a ride that is not yet visible.
- **Concurrent updates:** `Ride` has an `@Version` field. If a cancel and a match race, one loses with an optimistic-lock failure. The loser is retried (up to 3 attempts, each in a fresh transaction that re-reads the ride), and then sees the new status and ignores itself or applies cleanly. If all 3 attempts lose, the error is logged and the record goes to the retry/DLT path.
- **Retry and DLT:** any other failure is retried 3 times, 1s apart, then the record is published to `ride.matched.DLT` or `ride.match-failed.DLT` (3 partitions each). A message that cannot be deserialized goes straight to the DLT without retries.
