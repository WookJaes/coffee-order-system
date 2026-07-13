# Local Runtime

## Prerequisites

- Docker Desktop
- Java 17

## Start infrastructure

```bash
docker compose up -d
docker compose ps
```

The expected services are MySQL on `3306`, Redis on `6379`, and Kafka on `9092`.

## Start application

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

## Verify

```bash
curl http://localhost:8080/actuator/health
```

The health endpoint must return HTTP `200` and status `UP` after all infrastructure services are healthy.

## Stop infrastructure

```bash
docker compose down
```

Use `docker compose down -v` only when resetting local MySQL and Redis data is intentional.
