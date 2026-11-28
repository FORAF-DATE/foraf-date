# foraf-date

Monorepo for FORAF-DATE, a web, mobile, and desktop platform for managing ophthalmology appointments at the FORAF Foundation, with a microservices architecture on AWS.

## Repository structure

```text
foraf-date/
├── services/
│   ├── ms-auth/       # Authentication microservice (Maven module + wrapper)
│   │   ├── mvnw, mvnw.cmd
│   │   ├── .mvn/wrapper/
│   │   └── .gitattributes
│   └── ms-patients/   # Patients microservice (placeholder)
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
