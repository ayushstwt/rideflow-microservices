# location-service API

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
