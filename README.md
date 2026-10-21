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

## Roadmap

- [x] 1. Repository skeleton
- [x] 2. Shared events module
- [ ] 3. location-service
- [ ] 4. ride-service core
- [ ] 5. Kafka producer
- [ ] 6. matching-service
- [ ] 7. ride-service consumer
- [ ] 8. Reliability
- [ ] 9. Tests
- [ ] 10. API docs
- [ ] 11. Docker + CI
- [ ] 12. Final docs

## License

Released under the [MIT License](LICENSE).
