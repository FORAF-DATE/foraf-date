# Arquitectura y vías de comunicación del proyecto

Documentación basada en el código fuente presente en el repositorio (Spring Boot 4.1.1 / Java 21 / Maven Wrapper / Docker Compose).

## 1. Visión general

Monorepo de FORAF-DATE con una arquitectura de microservicios planeada para AWS. Hoy el único servicio implementado es `ms-auth`; `ms-patients` sigue siendo un placeholder.

| Servicio | Puerto (config) | Responsabilidad | Persistencia / dependencia |
|---|---:|---|---|
| `ms-auth` | `8080` | Registro de pacientes, sign-in de pacientes y staff, sign-out y sesiones | PostgreSQL `ms_auth` |
| `ms-auth-db` | `${DB_PORT}` desde `.env` | Base de datos local de MS-Auth | Volumen `ms_auth_pgdata` |
| `ms-patients` | — | Placeholder del microservicio de pacientes | `.gitkeep` |

Fuentes: `compose.yml:1-40`, `pom.xml:26-28`, `services/ms-auth/pom.xml:14`, `README.md:5-24`.

> Nota: la imagen de `ms-auth` se construye desde la raíz del repositorio porque el módulo depende del POM agregador (`services/ms-auth/pom.xml:7-12`). El archivo `.env` es la configuración real del proyecto; `.env.example` solo es la plantilla usada por GitHub Actions (`ci.yml:30-31`) y por el primer arranque local.

## 2. Vías de comunicación

### 2.1 HTTP síncrono cliente → ms-auth

Es la vía norte-sur pública. El cliente llama directamente a `ms-auth` en `http://localhost:8080`; todavía no existe un API Gateway.

- `POST /api/auth/patients/registration` → `PatientRegistrationController.java:21`
- `POST /api/auth/patients/login` → `PatientSignInController.java:27`
- `GET /api/auth/patients/me` → `PatientSignInController.java:34`
- `POST /api/auth/staff/login` → `StaffSignInController.java:20`
- `POST /api/auth/logout` → `SignOutController.java:20`
- `GET /actuator/health` → configurado en `application.properties:18-21`

No se usa `spring-boot-starter-security`. Las peticiones protegidas reciben un token opaco en `Authorization: Bearer <token>`; el servicio guarda solo su hash SHA-256 en `auth_sessions.token_hash`.

### 2.2 HTTP entre servicios

No existe todavía. `services/ms-patients/` contiene únicamente `.gitkeep`, por lo que no hay llamadas `ms-auth ↔ ms-patients`, balanceo `lb://` ni descubrimiento Eureka.

### 2.3 Persistencia y Flyway

`ms-auth` se conecta a PostgreSQL mediante `SPRING_DATASOURCE_*`, inyectado por Compose (`compose.yml:29-32`). Estas variables de entorno prevalecen sobre los valores filtrados en `application.properties:3-5`.

- Driver: `org.postgresql.Driver` (`application.properties:6`)
- Hibernate: `ddl-auto=validate` (`application.properties:8`), no crea ni modifica tablas.
- Flyway: habilitado con `classpath:db/migration` (`application.properties:12-13`).
- Schema: `V1__init_auth_schema.sql:1-25` crea `users`, `auth_sessions` y el índice por usuario.
- Seed opcional: `StaffSeedRunner` espera a Flyway y llama a `StaffAccountSeeder` solo cuando `auth.seed.enabled=true`; hace upsert por `username` y conserva el `id` existente.
- Sesiones: `expires_at`, `revoked_at` y TTL por defecto de 1440 minutos (`application.properties:15`).

### 2.4 Configuración por entorno

Compose carga automáticamente `.env` desde la raíz del proyecto. El runtime usa las variables que ya existen en ese archivo:

| Variable | Uso |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Inicialización del contenedor `ms-auth-db` |
| `DB_PORT` | Puerto host publicado de PostgreSQL |
| `DB_NAME`, `DB_USER`, `DB_PASSWORD` | Construcción de `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` para `ms-auth` |
| `DB_HOST`, `DB_PORT` | Arranque opcional de Maven desde la máquina local |
| `AUTH_SEED_ENABLED` | Activa el seed local de staff; por defecto `false` |
| `AUTH_SEED_ADMIN_PASSWORD`, `AUTH_SEED_RECEPTIONIST_PASSWORD`, `AUTH_SEED_OPHTHALMOLOGIST_PASSWORD` | Passwords de las tres cuentas de prueba; requeridos solo si el seed está activo |

`application.properties:16` deja `auth.seed.enabled=false` para no afectar CI ni tests; Compose mapea las variables `AUTH_SEED_*` a propiedades Spring relajadas (`auth.seed.*`). Ningún password real vive en `.env.example`, Properties, logs ni código fuente.

`services/ms-auth/pom.xml:79-97` lee `.env` para el filtrado de recursos. Por eso el Dockerfile copia `.env` solo en la etapa de build; `.dockerignore` excluye `.env.example` y artefactos locales, pero permite `.env`.

### 2.5 Observabilidad

Solo está expuesto Actuator health: `/actuator/health` con detalles siempre visibles y probe habilitado (`application.properties:18-21`). No hay Config Server, OpenTelemetry, Grafana/LGTM ni correlación de trazas en este repositorio.

## 3. Dockerfile y Compose

`services/ms-auth/Dockerfile:1-18` usa un build multi-etapa:

1. `eclipse-temurin:21-jdk-alpine` compila con el Maven Wrapper del módulo.
2. La etapa build copia el POM raíz, el POM del módulo, `mvnw`, `.mvn`, `src` y `.env`.
3. `./mvnw -B -DskipTests clean package` genera el JAR desde cero, sin depender de un `target/` previo.
4. `eclipse-temurin:21-jre-alpine` copia solo `ms-auth-*.jar`, expone `8080` y ejecuta `java -jar`.

`compose.yml:20-40` agrega el servicio `ms-auth`:

- `depends_on: ms-auth-db condition: service_healthy` evita iniciar la app antes de que PostgreSQL acepte conexiones.
- `SPRING_DATASOURCE_URL` apunta al nombre de servicio `ms-auth-db:5432`, no a `localhost`.
- Mapea `8080:8080`.
- Healthcheck usa `wget` contra `/actuator/health`.

## 4. Flujo completo de verificación local

1. `docker compose down -v` reinicia el volumen para garantizar migraciones pendientes.
2. Configurar `AUTH_SEED_ENABLED=true` y las tres passwords en `.env` si se quiere probar el seed; dejarlo en `false` para CI.
3. `docker compose up --build -d` construye la imagen y levanta PostgreSQL y MS-Auth.
4. `docker compose ps` muestra ambos contenedores healthy.
5. `docker compose logs ms-auth` muestra la migración `V1` aplicada por Flyway y, si corresponde, `Seeded 3 staff test accounts from environment variables`.
6. `GET /actuator/health` devuelve `200 OK` con `"status":"UP"`.
7. `POST /api/auth/patients/registration` crea una cuenta de prueba → `201`.
8. `POST /api/auth/patients/login` devuelve token opaco → `200`.
9. `GET /api/auth/patients/me` con el token → `200`.
10. `POST /api/auth/logout` con el token → `204` y escribe `revoked_at`.
11. `GET /api/auth/patients/me` con el mismo token → `401 SESSION_INVALID`.
12. `POST /api/auth/staff/login` con una cuenta `ADMINISTRATOR`, `RECEPTIONIST` u `OPHTHALMOLOGIST` → `200` con `token`, `name`, `username` y `role`.
13. `POST /api/auth/logout` con el token de staff → `204`; una petición protegida posterior con ese token → `401 SESSION_INVALID`.

## 5. Patrones aplicados

| Patrón | Dónde se ve | Propósito |
|---|---|---|
| Multi-stage Docker build | `services/ms-auth/Dockerfile` | Build reproducible y runtime pequeño sin `.env` en la imagen final |
| Database per service | `compose.yml` | MS-Auth es dueño de su propia base `ms_auth` |
| Controller → Service → Repository | paquetes de `ms-auth` | Separación de responsabilidades y DTOs de request/response |
| Opaque session token + hash | `auth_sessions.token_hash` | No almacenar el token en claro |
| Flyway + `ddl-auto=validate` | `application.properties:8,12-13` | Schema versionado; Hibernate no lo modifica |
| Healthcheck de contenedor | `/actuator/health` | Evidencia de arranque con DB disponible |
| Configuración por entorno | `.env`, `compose.yml` | Sin credenciales hardcodeadas en Docker ni en el código |
| ApplicationRunner seed | `StaffSeedRunner.java`, `StaffAccountSeeder.java` | Cuentas staff de desarrollo repetibles, con passwords solo por entorno |

## 6. Estado actual y puntos a tener en cuenta

1. No hay API Gateway, Eureka, Config Server, Keycloak, RabbitMQ ni OTLP en este repositorio.
2. `ms-patients` es un placeholder; todavía no hay comunicación inter-servicios.
3. `429 RATE_LIMITED` y registro de staff siguen pendientes de backend; el login de staff ya está implementado y las cuentas internas de prueba pueden sembrarse con `AUTH_SEED_ENABLED=true`.
4. `AUTH-DOC-02` ya es v0.8 y define `POST /api/auth/logout` con `204 No Content`.
5. `.env.example` no se consume en Docker ni en runtime; solo genera `.env` en CI y sirve como plantilla local.
6. El volumen `ms_auth_pgdata` conserva cuentas y sesiones entre reinicios; usar `docker compose down -v` para una verificación limpia.

## 7. Resumen de la comunicación en una línea

El cliente habla REST directo con `ms-auth`, el contenedor resuelve PostgreSQL por el nombre `ms-auth-db`, Flyway aplica el schema, el token opaco se guarda como hash y el logout revoca la sesión; todo se levanta con `docker compose up --build`.
