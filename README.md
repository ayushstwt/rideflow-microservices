# rideflow-microservices

An Uber-like ride-hailing backend built with event-driven Spring Boot microservices.

![Status](https://img.shields.io/badge/status-under%20active%20development-orange)

**Status: under active development**

## Services

| Service            | Port | Responsibility |
|--------------------|------|----------------|
| location-service   | 8082 | Tracks driver locations and answers nearby-driver queries (Redis Geo) |
| ride-service       | 8083 | Ride lifecycle: request, persistence, status updates (MySQL) |
| matching-service   | 8084 | Consumes ride requests and matches them with nearby drivers |

## Architecture

## Tech Stack

- Java 17
- Spring Boot 3.x
- Spring Kafka
- Spring Data Redis (Geo)
- Spring Data JPA + MySQL
- OpenFeign
- Maven, Lombok

## Project structure

```
rideflow-microservices/
├── pom.xml                  # parent POM (rideflow-parent)
├── docker-compose.yml       # infra definition (Redis, MySQL, Kafka)
├── .env.example
├── docs/
├── libs/
│   └── common-events/       # shared Kafka event DTOs and topic names
└── services/
    ├── location-service/
    ├── ride-service/
    └── matching-service/
```

## Concurrency

Many ride requests are matched at the same time, and two of them must never get the same driver. Matching therefore asks location-service to *reserve* a driver instead of just reading the nearby list. The check and the write happen inside one Redis Lua script (`reserve_driver.lua`), which Redis runs atomically, so concurrent requests cannot both see a driver as free. The script is idempotent per ride (a redelivered `ride.requested` gets the same driver), reserved drivers are hidden from nearby searches, and the reservation is released when ride-service publishes `ride.closed`. A TTL (`location.reservation-ttl-seconds`, 30 min) frees the driver anyway if a ride is never closed. See [docs/api/location-service.md](docs/api/location-service.md).

## Roadmap

- [x] 1. Repository skeleton
- [x] 2. Shared events module
- [x] 3. location-service
- [x] 4. ride-service core
- [x] 5. Kafka producer
- [x] 6. matching-service
- [x] 7. ride-service consumer
- [ ] 8. Reliability
  - [x] 8A. Driver reservation (atomic Lua script, `ride.closed` release)
  - [ ] 8B. Ride closing and retries (ride-service)
- [ ] 9. Tests
- [ ] 10. API docs
- [ ] 11. Docker + CI
- [ ] 12. Final docs

## License

Released under the [MIT License](LICENSE).
