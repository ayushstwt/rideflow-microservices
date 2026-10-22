# location-service API

> **Source of truth:** the generated OpenAPI docs, at http://localhost:8082/swagger-ui.html (JSON: `/v3/api-docs`). This page adds background and examples and may lag behind. A Postman collection is in [docs/postman](../postman).

Base URL: `http://localhost:8082/api/v1/locations`

Driver positions are stored in a Redis GEO set. A driver counts as online only while a heartbeat key
(refreshed on every update, TTL `location.driver-ttl-seconds`, default 30s) exists.

## POST /drivers/update

Create or refresh a driver's position and heartbeat.

```bash
curl -X POST http://localhost:8082/api/v1/locations/drivers/update \
  -H "Content-Type: application/json" \
  -d '{"driverId":"driver-1","latitude":12.9716,"longitude":77.5946}'
```

Response `200 OK`:

```json
{"status":"updated"}
```

Validation failure `400 Bad Request` (field -> message):

```json
{"latitude":"latitude must be <= 90"}
```

## GET /drivers/nearby

Online drivers within a radius, nearest first.

| Param       | Required | Default | Constraint     |
|-------------|----------|---------|----------------|
| `latitude`  | yes      |         | -90 to 90      |
| `longitude` | yes      |         | -180 to 180    |
| `radius`    | no       | 5.0     | 0.1 to 50 (km) |
| `limit`     | no       | 10      | 1 to 50        |

```bash
curl "http://localhost:8082/api/v1/locations/drivers/nearby?latitude=12.9716&longitude=77.5946&radius=5.0&limit=10"
```

Response `200 OK`:

```json
[
  {
    "driverId": "driver-1",
    "latitude": 12.9716,
    "longitude": 77.5946,
    "distanceInKm": 0.0
  }
]
```

Out-of-range parameters return `400 Bad Request`:

```json
{"radius":"must be less than or equal to 50.0"}
```

Drivers that are currently reserved for a ride (see below) are **excluded**, in addition to drivers whose heartbeat expired.

## POST /drivers/reserve

Reserve one driver for a ride. `candidateDriverIds` is ordered best first; the first candidate that is still free wins.

| Field                | Constraint                    |
|----------------------|-------------------------------|
| `rideId`             | not blank                     |
| `candidateDriverIds` | not empty, at most 20, no blanks |

```bash
curl -X POST http://localhost:8082/api/v1/locations/drivers/reserve \
  -H "Content-Type: application/json" \
  -d '{"rideId":"ride-1","candidateDriverIds":["driver-1","driver-2","driver-3"]}'
```

Response `200 OK`:

```json
{"driverId":"driver-2"}
```

Every candidate is already reserved: `409 Conflict`:

```json
{"error":"NO_DRIVER_AVAILABLE"}
```

Calling it again for the same `rideId` returns the **same** driver, whatever candidates are sent (idempotent), so a redelivered ride request never reserves a second driver.

### Why one driver is never matched to two rides

Two matching requests for different rides can arrive at the same moment and both see the same driver as free. A check-then-set done from Java (`EXISTS` then `SET`) would let both win. Instead the whole decision runs as **one Lua script** (`scripts/reserve_driver.lua`), and Redis executes a script without running any other command in between:

1. If `ride:assignment:{rideId}` exists, return that driver (idempotency).
2. Otherwise walk the candidates in order and take the first without a `driver:reservation:{driverId}` key.
3. Write both keys with the TTL in the same script and return the driver, or nothing if all are taken.

| Key                          | Value    | Purpose |
|------------------------------|----------|---------|
| `driver:reservation:{driverId}` | `rideId` | marks the driver busy; hides them from `/drivers/nearby` |
| `ride:assignment:{rideId}`      | `driverId` | idempotency, and lets a release find the driver from the ride |

The keys are not declared as Redis Cluster keys, so this requires a standalone Redis (the setup in `docker-compose.yml`).

### TTL safety net

Both keys expire after `location.reservation-ttl-seconds` (default `1800`, 30 min). Normally the reservation is released by the `ride.closed` event long before that; the TTL only makes sure a crashed or never-closed ride cannot block a driver forever.

## DELETE /reservations/{rideId}

Release the driver held for a ride. Mainly for ops and testing; the normal path is the `ride.closed` event below.

```bash
curl -X DELETE http://localhost:8082/api/v1/locations/reservations/ride-1
```

Response: `204 No Content`, also for an unknown ride or when called twice. The driver's reservation key is deleted only while it still holds this `rideId` (a second Lua script, `scripts/release_driver.lua`), so a reservation that already expired and was taken by another ride is never removed by mistake.

## Consumed: `ride.closed`

Produced by ride-service when a ride reaches a terminal state (added in Step 8B). Group `location-service-group`, offsets committed after each record. The listener calls the same release as the endpoint above and never fails for an unknown `rideId`.

```json
{
  "rideId": "ride-1",
  "riderId": "rider-42",
  "driverId": "driver-2",
  "finalStatus": "COMPLETED",
  "closedAt": "2026-01-01T10:30:00Z"
}
```

Processing errors are retried 3 times, 1s apart, then the record goes to `ride.closed.DLT`.

## DELETE /drivers/{driverId}

Remove a driver from the geo set and delete the heartbeat (e.g. driver goes offline).

```bash
curl -X DELETE http://localhost:8082/api/v1/locations/drivers/driver-1
```

Response: `204 No Content`.

## Errors

Unexpected failures return `500` with a generic body and no stack trace:

```json
{"error":"Internal server error"}
```
