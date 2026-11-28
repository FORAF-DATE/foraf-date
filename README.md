# foraf-date

Monorepo for FORAF-DATE, a web, mobile, and desktop platform for managing ophthalmology appointments at the FORAF Foundation, with a microservices architecture on AWS.

## Repository structure

```text
foraf-date/
├── services/
│   ├── ms-auth/       # Authentication microservice (Maven module + wrapper)
│   │   ├── mvnw, mvnw.cmd
│   │   ├── .mvn/wrapper/
│   │   ├── .gitattributes
│   │   └── src/main/resources/db/migration/  # Flyway migrations
│   └── ms-patients/   # Patients microservice (placeholder)
├── docker-compose.yml # Local PostgreSQL for MS-Auth
├── .env.example       # Example environment variables
├── pom.xml            # Root Maven aggregator POM
├── .github/workflows/ci.yml
├── .gitignore         # Global ignore rules for every module
└── README.md
```

Each directory under `services/` is an independent Maven project (a microservice). `ms-auth` is the first module; `ms-patients` remains a placeholder tracked with `.gitkeep` until its project is created.

## Root pom.xml

The root `pom.xml` is the aggregator POM for the microservices monorepo. It uses `<packaging>pom</packaging>` with coordinates `org.forafdate:foraf-date-parent:1.0.0-SNAPSHOT`, inherits Spring Boot `4.1.1`, and sets Java 21 for every module.

Each microservice has its own Maven Wrapper. For `ms-auth`:

```bash
cd services/ms-auth
./mvnw compile
```

On Windows use `mvnw.cmd compile`. The root `pom.xml` remains the monorepo aggregator for builds with Maven installed.

A `<module>` entry is added for the remaining services only when each one has its own `pom.xml`.

## MS-Auth: Local PostgreSQL, Flyway, and Health Check (AUTH-DEV-01-02 / AUTH-DEV-01-03)

### Prerequisites

- Docker Desktop / Docker Compose
- Java 21
- Port `5432` available for PostgreSQL (or adjust `DB_PORT` in `.env`)
- Port `8080` available for MS-Auth

### Setup

1. Copy the example environment variables:

```bash
cp .env.example .env
```

On Windows PowerShell you can also use `Copy-Item .env.example .env`. The `.env` file contains local credentials and is ignored by Git; `.env.example` has reference values.

2. Start PostgreSQL with Docker Compose from the repository root:

```bash
docker compose up -d
```

This creates the dedicated database `ms_auth`, the development user, the volume `ms_auth_pgdata`, and exposes PostgreSQL on the `DB_PORT` defined in `.env`.

3. Start MS-Auth:

```bash
cd services/ms-auth
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd spring-boot:run`.

The Maven build automatically loads the `.env` file from the repository root and injects the database connection properties into `application.properties` during the resource filtering phase.

### Flyway Verification

- With an empty database, Flyway applies `V1__init_auth_schema.sql` and registers the migration in `flyway_schema_history`.
- When restarting the service with the same Docker volume, Flyway detects the applied migration and startup continues normally.
- The configuration uses `spring.jpa.hibernate.ddl-auto=validate`; Hibernate does not create or modify the schema.

### Health check (Actuator)

With PostgreSQL and MS-Auth running, verify the service status:

```bash
curl http://localhost:8080/actuator/health
```

Expected response with database available (HTTP 200):

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    },
    "ssl": {
      "status": "UP"
    }
  }
}
```

If PostgreSQL stops while MS-Auth is still running, the endpoint reports the database as unavailable. The `db` indicator changes to `DOWN` and the health check responds with status `DOWN` (HTTP 503):

```bash
docker compose stop ms-auth-db
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{
  "status": "DOWN",
  "components": {
    "db": {
      "status": "DOWN",
      "details": {
        "error": "org.springframework.jdbc.CannotGetJdbcConnectionException: ..."
      }
    }
  }
}
```

To restore the service, start PostgreSQL again:

```bash
docker compose start ms-auth-db
```

### Patient Registration

With PostgreSQL and MS-Auth running, register a patient:

```bash
curl -i -X POST http://localhost:8080/api/auth/patients/registration \
  -H "Content-Type: application/json" \
  -d '{"username":"ana.vera","password":"Example password 42","passwordConfirmation":"Example password 42"}'
```

`201 Created`:

```json
{"username":"ana.vera"}
```

The backend assigns `PATIENT` and `ACTIVE`, stores a BCrypt hash in `users.password_hash` and discards `passwordConfirmation`. PostgreSQL enforces username uniqueness, so concurrent requests for the same username create only one account.

Field errors use `code` and `field`:

```json
{"code":"USERNAME_TAKEN","field":"username"}
```

| Status | code | field |
|---|---|---|
| 400 | `REQUIRED` | `username`, `password` or `passwordConfirmation` |
| 400 | `USERNAME_LENGTH` | `username` |
| 400 | `PASSWORD_LENGTH` | `password` |
| 400 | `PASSWORD_MISMATCH` | `passwordConfirmation` |
| 409 | `USERNAME_TAKEN` | `username` |
| 400 | `MALFORMED_REQUEST` | omitted |

### Patient Sign-In

Sign in with an active patient account:

```bash
curl -i -X POST http://localhost:8080/api/auth/patients/login \
  -H "Content-Type: application/json" \
  -d '{"username":"ana.vera","password":"Example password 42"}'
```

`200 OK`:

```json
{"token":"<opaque-token>","username":"ana.vera"}
```

Only the SHA-256 hexadecimal hash is stored in `auth_sessions.token_hash`. Sessions expire after 24 hours by default (`auth.session.ttl-minutes=1440`).

Protected request:

```bash
curl -i http://localhost:8080/api/auth/patients/me \
  -H "Authorization: Bearer <opaque-token>"
```

`200 OK`:

```json
{"username":"ana.vera"}
```

| Status | code | field |
|---|---|---|
| 400 | `REQUIRED` | `username` or `password` |
| 400 | `MALFORMED_REQUEST` | omitted |
| 422 | `INVALID_CREDENTIALS` | omitted |
| 403 | `ACCOUNT_INACTIVE` | omitted |
| 403 | `ACCOUNT_DISABLED` | omitted |
| 403 | `ACCOUNT_TYPE_FORBIDDEN` | omitted |
| 401 | `SESSION_REQUIRED` | omitted |
| 401 | `SESSION_INVALID` | omitted |
| 401 | `SESSION_EXPIRED` | omitted |

### Communication and Proposed Initial Schema

MS-Auth exposes synchronous REST for patient and staff registration/login (AUTH-DOC-02). In local development it uses PostgreSQL with opaque tokens: the client receives the token and the database stores its hash, expiration, and revocation.

The V1 schema is a proposed implementation based on `FORAF-DATE_MS-Auth_Modelo_de_Datos.md`, pending formal validation in AUTH-DOC-01:

| Table | Purpose | Key fields |
|---|---|---|
| `users` | Access account and role | `id`, `username` (unique, 4-30), `password_hash`, `display_name`, `role`, `status`, `created_at` |
| `auth_sessions` | Local opaque sessions | `id`, `user_id` (FK), `token_hash` (SHA-256, unique), `created_at`, `expires_at`, `revoked_at` |

Relationship: a `user` can have zero or many `auth_sessions`; each session belongs to a single user.
