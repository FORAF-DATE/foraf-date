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
├── compose.yml        # Local PostgreSQL and MS-Auth via Docker Compose
├── .env.example       # Example environment variables
├── pom.xml            # Root Maven aggregator POM
├── .github/workflows/ci.yml
├── .gitignore         # Global ignore rules for every module
└── README.md
```

Each directory under `services/` is an independent Maven project (a microservice). `ms-auth` is the first module; `ms-patients` remains a placeholder tracked with `.gitkeep` until its project is created. See `docs/ARCHITECTURE.md` for the architecture and communication paths.

## Root pom.xml

The root `pom.xml` is the aggregator POM for the microservices monorepo. It uses `<packaging>pom</packaging>` with coordinates `org.forafdate:foraf-date-parent:1.0.0-SNAPSHOT`, inherits Spring Boot `4.1.1`, and sets Java 21 for every module.

Each microservice has its own Maven Wrapper. For `ms-auth`:

```bash
cd services/ms-auth
./mvnw compile
```

On Windows use `mvnw.cmd compile`. The root `pom.xml` remains the monorepo aggregator for builds with Maven installed.

A `<module>` entry is added for the remaining services only when each one has its own `pom.xml`.

## MS-Auth: Docker Compose, PostgreSQL, Flyway, and Health Check (AUTH-DEV-01-02 / AUTH-DEV-01-03 / AUTH-DEV-02-04)

### Prerequisites

- Docker Desktop / Docker Compose
- Port `5434` available for PostgreSQL (or adjust `DB_PORT` in `.env`)
- Port `8080` available for MS-Auth

### Setup

1. Copy the example environment variables if `.env` does not exist:

```bash
cp .env.example .env
```

On Windows PowerShell use `Copy-Item .env.example .env`. `.env.example` is only a template for first-time setup and GitHub Actions (`ci.yml:31`); Maven and Docker Compose read `.env` (`services/ms-auth/pom.xml:91`, `compose.yml`). `.env` contains local credentials and is ignored by Git.

2. Build and start PostgreSQL and MS-Auth from the repository root:

```bash
docker compose up --build -d
```

This builds `services/ms-auth/Dockerfile`, starts the dedicated database `ms_auth`, applies Flyway migrations on MS-Auth startup, creates the volume `ms_auth_pgdata`, publishes PostgreSQL on `DB_PORT` from `.env`, and publishes MS-Auth on port `8080`.

3. Check container status:

```bash
docker compose ps
```

Both `ms-auth-db` and `ms-auth` should be up, with their healthchecks reported as healthy.

4. Optional local Maven run without the MS-Auth container: with PostgreSQL already running, execute:

```bash
cd services/ms-auth
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd spring-boot:run`. Maven loads `.env` from the repository root and injects the database connection properties into `application.properties` during resource filtering.

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

### Docker Compose verification (AUTH-DEV-02-04)

From the repository root:

```bash
docker compose down -v
docker compose up --build -d
docker compose ps
docker compose logs ms-auth
curl -i http://localhost:8080/actuator/health
```

After Flyway applies `V1__init_auth_schema.sql`, health returns `200 OK` with `"status":"UP"`. Then exercise the patient API with a unique username:

```bash
curl -i -X POST http://localhost:8080/api/auth/patients/registration \
  -H "Content-Type: application/json" \
  -d '{"username":"docker.test","password":"Example password 42","passwordConfirmation":"Example password 42"}'

curl -i -X POST http://localhost:8080/api/auth/patients/login \
  -H "Content-Type: application/json" \
  -d '{"username":"docker.test","password":"Example password 42"}'

curl -i http://localhost:8080/api/auth/patients/me \
  -H "Authorization: Bearer <opaque-token>"

curl -i -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer <opaque-token>"

curl -i http://localhost:8080/api/auth/patients/me \
  -H "Authorization: Bearer <opaque-token>"
```

Expected statuses: `201`, `200`, `200`, `204`, and `401 SESSION_INVALID` for the final protected request.

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

### Staff Sign-In

Internal staff accounts are created outside this endpoint; locally you can use the optional seed described below. Sign in from Electron with an active account:

```bash
curl -i -X POST http://localhost:8080/api/auth/staff/login \
  -H "Content-Type: application/json" \
  -d '{"username":"lucia.torres","password":"Example password 42"}'
```

`200 OK`:

```json
{"token":"<opaque-token>","name":"Lucía Torres","username":"lucia.torres","role":"RECEPTIONIST"}
```

Supported roles are `ADMINISTRATOR`, `RECEPTIONIST` and `OPHTHALMOLOGIST`. A patient account using this route receives `403 ACCOUNT_TYPE_FORBIDDEN`. Reuse `POST /api/auth/logout` with the returned token to revoke the session.

| Status | code | field |
|---|---|---|
| 400 | `REQUIRED` | `username` or `password` |
| 400 | `MALFORMED_REQUEST` | omitted |
| 422 | `INVALID_CREDENTIALS` | omitted |
| 403 | `ACCOUNT_INACTIVE` | omitted |
| 403 | `ACCOUNT_DISABLED` | omitted |
| 403 | `ACCOUNT_TYPE_FORBIDDEN` | omitted |

### Internal Staff Seed (AUTH-DEV-04)

Local development can seed three active internal staff accounts after Flyway migrations. The seed is disabled by default (`auth.seed.enabled=false`) and only runs when `AUTH_SEED_ENABLED=true` is present in `.env`:

```properties
AUTH_SEED_ENABLED=true
AUTH_SEED_ADMIN_PASSWORD=<local-password>
AUTH_SEED_RECEPTIONIST_PASSWORD=<local-password>
AUTH_SEED_OPHTHALMOLOGIST_PASSWORD=<local-password>
```

`docker compose up --build -d` passes these variables to `ms-auth`. The `ApplicationRunner` is idempotent: it creates a missing account by username, updates an existing one in place, preserves its `id`, and hashes the configured password with the same `PasswordEncoder` used by registration. Every enabled startup rotates the password hash and restores `ACTIVE` status, `display_name` and `role`.

| Username | display_name | role |
|---|---|---|
| `admin.test` | `Ana Ruiz` | `ADMINISTRATOR` |
| `receptionist.test` | `Lucía Torres` | `RECEPTIONIST` |
| `ophthalmologist.test` | `Mateo Silva` | `OPHTHALMOLOGIST` |

No password is hardcoded in the seed source, `.env.example`, Spring properties or logs. `.env.example` only contains empty placeholders for CI and first-time setup. A missing or blank required password fails startup with `Missing required seed password: <VARIABLE_NAME>` before any account is saved.

To verify, start with a clean volume and sign in as `receptionist.test` using the configured password:

```bash
docker compose down -v
docker compose up --build -d
curl -i -X POST http://localhost:8080/api/auth/staff/login \
  -H "Content-Type: application/json" \
  -d '{"username":"receptionist.test","password":"<local-password>"}'
```

Expected: `200 OK` with a `token`, `name`, `username` and `role` for the seeded account.

### Sign-Out

Revoke the presented token:

```bash
curl -i -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer <opaque-token>"
```

`204 No Content` with no response body. Only that session gets `auth_sessions.revoked_at`; other valid sessions for the same user keep access. A revoked token receives `401 SESSION_INVALID` on later protected requests.

| Status | code | field |
|---|---|---|
| 401 | `SESSION_REQUIRED` | omitted |
| 401 | `SESSION_INVALID` | omitted |
| 401 | `SESSION_EXPIRED` | omitted |

`POST /api/auth/logout` and `204 No Content` are implemented for FOR-67 and defined in AUTH-DOC-02 v0.8.

### Communication and Proposed Initial Schema

MS-Auth exposes synchronous REST for patient registration, patient and staff sign-in, and sign-out (AUTH-DOC-02). Staff registration remains pending. In local development it uses PostgreSQL with opaque tokens: the client receives the token and the database stores its hash, expiration, and revocation.

The V1 schema is a proposed implementation based on `FORAF-DATE_MS-Auth_Modelo_de_Datos.md`, pending formal validation in AUTH-DOC-01:

| Table | Purpose | Key fields |
|---|---|---|
| `users` | Access account and role | `id`, `username` (unique, 4-30), `password_hash`, `display_name`, `role`, `status`, `created_at` |
| `auth_sessions` | Local opaque sessions | `id`, `user_id` (FK), `token_hash` (SHA-256, unique), `created_at`, `expires_at`, `revoked_at` |

Relationship: a `user` can have zero or many `auth_sessions`; each session belongs to a single user.
