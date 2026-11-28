# Project architecture and communication paths

Documentation based on the source code currently in the repository (Spring Boot 4.1.1 / Java 21 / Maven Wrapper / Docker Compose).

## 1. Overview

FORAF-DATE monorepo with a planned microservices architecture for AWS. Today the only implemented service is `ms-auth`; `ms-patients` is still a placeholder.

| Service | Port (config) | Responsibility | Persistence / dependency |
|---|---:|---|---|
| `ms-auth` | `8080` | Patient registration, patient and staff sign-in, sign-out, and sessions | PostgreSQL `ms_auth` |
| `ms-auth-db` | `${DB_PORT}` from `.env` | Local MS-Auth database | `ms_auth_pgdata` volume |
| `ms-patients` | — | Patients microservice placeholder | `.gitkeep` |

Sources: `compose.yml:1-46`, `pom.xml:26-28`, `services/ms-auth/pom.xml:14`, `README.md:5-24`.

> Note: the `ms-auth` image is built from the repository root because the module depends on the aggregator POM (`services/ms-auth/pom.xml:7-12`). The `.env` file is the real project configuration; `.env.example` is only the template used by GitHub Actions (`ci.yml:30-31`) and by the first local startup.

## 2. Communication paths

### 2.1 Synchronous HTTP client → ms-auth

This is the public north-south path. The client calls `ms-auth` directly at `http://localhost:8080`; there is no API Gateway yet.

- `POST /api/auth/patients/registration` → `PatientRegistrationController.java:21`
- `POST /api/auth/patients/login` → `PatientSignInController.java:27`
- `GET /api/auth/patients/me` → `PatientSignInController.java:34`
- `POST /api/auth/staff/login` → `StaffSignInController.java:20`
- `POST /api/auth/logout` → `SignOutController.java:20`
- `GET /actuator/health` → configured in `application.properties:18-21`

`spring-boot-starter-security` is not used. Protected requests receive an opaque token in `Authorization: Bearer <token>`; the service stores only its SHA-256 hash in `auth_sessions.token_hash`.

### 2.2 Service-to-service HTTP

Not implemented yet. `services/ms-patients/` contains only `.gitkeep`, so there are no `ms-auth ↔ ms-patients` calls, `lb://` balancing, or Eureka discovery.

### 2.3 Persistence and Flyway

`ms-auth` connects to PostgreSQL through `SPRING_DATASOURCE_*`, injected by Compose (`compose.yml:29-32`). These environment variables override the values filtered into `application.properties:3-5`.

- Driver: `org.postgresql.Driver` (`application.properties:6`)
- Hibernate: `ddl-auto=validate` (`application.properties:8`); it does not create or modify tables.
- Flyway: enabled with `classpath:db/migration` (`application.properties:12-13`).
- Schema: `V1__init_auth_schema.sql:1-25` creates `users`, `auth_sessions`, and the index by user.
- Optional seed: `StaffSeedRunner` waits for Flyway and calls `StaffAccountSeeder` only when `auth.seed.enabled=true`; it upserts by `username` and preserves the existing `id`.
- Sessions: `expires_at`, `revoked_at`, and a default TTL of 1440 minutes (`application.properties:15`).

### 2.4 Environment configuration

Compose automatically loads `.env` from the repository root. The runtime uses the variables already present in that file:

| Variable | Usage |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Initialization of the `ms-auth-db` container |
| `DB_PORT` | Published host port for PostgreSQL |
| `DB_NAME`, `DB_USER`, `DB_PASSWORD` | Build of `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` for `ms-auth` |
| `DB_HOST`, `DB_PORT` | Optional Maven startup from the local machine |
| `AUTH_SEED_ENABLED` | Enables the local staff seed; defaults to `false` |
| `AUTH_SEED_ADMIN_PASSWORD`, `AUTH_SEED_RECEPTIONIST_PASSWORD`, `AUTH_SEED_OPHTHALMOLOGIST_PASSWORD` | Passwords for the three test accounts; required only when the seed is enabled |

`application.properties:16` leaves `auth.seed.enabled=false` so CI and tests are not affected; Compose maps the `AUTH_SEED_*` variables to relaxed Spring properties (`auth.seed.*`). No real password lives in `.env.example`, Properties, logs, or source code.

`services/ms-auth/pom.xml:79-97` reads `.env` for resource filtering. That is why the Dockerfile copies `.env` only in the build stage; `.dockerignore` excludes `.env.example` and local artifacts, but allows `.env`.

### 2.5 Observability

Only Actuator health is exposed: `/actuator/health` with details always visible and probes enabled (`application.properties:18-21`). There is no Config Server, OpenTelemetry, Grafana/LGTM, or trace correlation in this repository.

## 3. Dockerfile and Compose

`services/ms-auth/Dockerfile:1-18` uses a multi-stage build:

1. `eclipse-temurin:21-jdk-alpine` compiles with the module's Maven Wrapper.
2. The build stage copies the root POM, module POM, `mvnw`, `.mvn`, `src`, and `.env`.
3. `./mvnw -B -DskipTests clean package` builds the JAR from scratch, without relying on a previous `target/`.
4. `eclipse-temurin:21-jre-alpine` copies only `ms-auth-*.jar`, exposes `8080`, and runs `java -jar`.

`compose.yml:20-43` adds the `ms-auth` service:

- `depends_on: ms-auth-db condition: service_healthy` prevents the app from starting before PostgreSQL accepts connections.
- `SPRING_DATASOURCE_URL` points to the service name `ms-auth-db:5432`, not `localhost`.
- It maps `8080:8080`.
- The healthcheck uses `wget` against `/actuator/health`.

## 4. Complete local verification flow

1. `docker compose down -v` resets the volume to guarantee pending migrations.
2. Set `AUTH_SEED_ENABLED=true` and the three passwords in `.env` if you want to test the seed; leave it `false` for CI.
3. `docker compose up --build -d` builds the image and starts PostgreSQL and MS-Auth.
4. `docker compose ps` shows both containers as healthy.
5. `docker compose logs ms-auth` shows the `V1` migration applied by Flyway and, when applicable, `Seeded 3 staff test accounts from environment variables`.
6. `GET /actuator/health` returns `200 OK` with `"status":"UP"`.
7. `POST /api/auth/patients/registration` creates a test account → `201`.
8. `POST /api/auth/patients/login` returns an opaque token → `200`.
9. `GET /api/auth/patients/me` with the token → `200`.
10. `POST /api/auth/logout` with the token → `204` and writes `revoked_at`.
11. `GET /api/auth/patients/me` with the same token → `401 SESSION_INVALID`.
12. `POST /api/auth/staff/login` with an `ADMINISTRATOR`, `RECEPTIONIST`, or `OPHTHALMOLOGIST` account → `200` with `token`, `name`, `username`, and `role`.
13. `POST /api/auth/logout` with the staff token → `204`; a later protected request with that token → `401 SESSION_INVALID`.

## 5. Applied patterns

| Pattern | Where | Purpose |
|---|---|---|
| Multi-stage Docker build | `services/ms-auth/Dockerfile` | Reproducible build and small runtime with no `.env` in the final image |
| Database per service | `compose.yml` | MS-Auth owns its `ms_auth` database |
| Controller → Service → Repository | `ms-auth` packages | Separation of responsibilities and request/response DTOs |
| Opaque session token + hash | `auth_sessions.token_hash` | Never store the token in plain text |
| Flyway + `ddl-auto=validate` | `application.properties:8,12-13` | Versioned schema; Hibernate does not modify it |
| Container healthcheck | `/actuator/health` | Startup evidence with the database available |
| Environment configuration | `.env`, `compose.yml` | No hardcoded credentials in Docker or code |
| ApplicationRunner seed | `StaffSeedRunner.java`, `StaffAccountSeeder.java` | Repeatable development staff accounts with passwords only from the environment |

## 6. Current state and notes

1. There is no API Gateway, Eureka, Config Server, Keycloak, RabbitMQ, or OTLP in this repository.
2. `ms-patients` is a placeholder; there is still no service-to-service communication.
3. `429 RATE_LIMITED` and staff registration are still pending on the backend; staff sign-in is implemented and internal test accounts can be seeded with `AUTH_SEED_ENABLED=true`.
4. `AUTH-DOC-02` is already v0.8 and defines `POST /api/auth/logout` with `204 No Content`.
5. `.env.example` is not consumed by Docker or runtime; it only generates `.env` in CI and serves as a local template.
6. The `ms_auth_pgdata` volume keeps accounts and sessions across restarts; use `docker compose down -v` for a clean verification.

## 7. One-line communication summary

The client talks REST directly to `ms-auth`, the container resolves PostgreSQL by the service name `ms-auth-db`, Flyway applies the schema, the opaque token is stored as a hash, and logout revokes the session; everything starts with `docker compose up --build`.
