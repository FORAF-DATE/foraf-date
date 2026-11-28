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
├── docker-compose.yml # PostgreSQL local para MS-Auth
├── .env.example       # Variables de entorno de ejemplo
├── pom.xml            # Root Maven aggregator POM
├── .github/workflows/ci.yml
├── .gitignore         # Global ignore rules for every module
└── README.md
```

Each directory under `services/` is an independent Maven project (a microservice). `ms-auth` is the first module; `ms-patients` remains a placeholder tracked with `.gitkeep` until its project is created.

## Root pom.xml

The root `pom.xml` is the aggregator POM for the microservices monorepo. It uses `<packaging>pom</packaging>` with coordinates `org.forafdate:foraf-date-parent:1.0.0-SNAPSHOT`, inherits Spring Boot `4.1.1`, and sets Java 21 for every module.

Cada microservicio lleva su propio Maven Wrapper. Para `ms-auth`:

```bash
cd services/ms-auth
./mvnw compile
```

En Windows usa `mvnw.cmd compile`. El `pom.xml` raíz sigue siendo el agregador del monorepo para builds con Maven instalado.

A `<module>` entry is added for the remaining services only when each one has its own `pom.xml`.

## MS-Auth: PostgreSQL local y Flyway (AUTH-DEV-01-02)

### Prerrequisitos

- Docker Desktop / Docker Compose
- Java 21

### Configuración

1. Copia las variables de entorno de ejemplo:

```bash
cp .env.example .env
```

En Windows PowerShell también puedes usar `Copy-Item .env.example .env`. El archivo `.env` contiene credenciales locales y está ignorado por Git; `.env.example` tiene los valores de referencia.

2. Levanta PostgreSQL con Docker Compose desde la raíz del repositorio:

```bash
docker compose up -d
```

Esto crea la base dedicada `ms_auth`, el usuario de desarrollo, el volumen `ms_auth_pgdata` y expone PostgreSQL en el `DB_PORT` definido en el `.env`.

3. Arranca MS-Auth:

```bash
cd services/ms-auth
./mvnw spring-boot:run
```

En Windows usa `mvnw.cmd spring-boot:run`.

### Verificación de Flyway

- Con una base vacía, Flyway aplica `V1__init_auth_schema.sql` y registra la migración en `flyway_schema_history`.
- Al reiniciar el servicio con el mismo volumen Docker, Flyway detecta la migración aplicada y el arranque continúa normalmente.
- La configuración usa `spring.jpa.hibernate.ddl-auto=validate`; Hibernate no crea ni modifica el esquema.

### Comunicación y esquema inicial propuesto

MS-Auth expone REST síncrono para registro/login de pacientes y personal (AUTH-DOC-02). En desarrollo local usa PostgreSQL con token opaco: el cliente recibe el token y la base guarda su hash, vencimiento y revocación.

El esquema V1 es una propuesta de implementación basada en `FORAF-DATE_MS-Auth_Modelo_de_Datos.md`, pendiente de validación formal en AUTH-DOC-01:

| Tabla | Propósito | Campos clave |
|---|---|---|
| `users` | Cuenta de acceso y rol | `id`, `username` (único, 4-30), `password_hash`, `display_name`, `role`, `status`, `created_at` |
| `auth_sessions` | Sesiones locales opacas | `id`, `user_id` (FK), `token_hash` (SHA-256, único), `created_at`, `expires_at`, `revoked_at` |

Relación: un `user` puede tener cero o muchas `auth_sessions`; cada sesión pertenece a un solo usuario.
