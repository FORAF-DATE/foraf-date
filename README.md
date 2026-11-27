# foraf-date

Monorepo for FORAF-DATE, a web, mobile, and desktop platform for managing ophthalmology appointments at the FORAF Foundation, with a microservices architecture on AWS.

## Repository structure

```text
foraf-date/
├── services/
│   ├── ms-auth/       # Authentication microservice (placeholder)
│   └── ms-patients/   # Patients microservice (placeholder)
├── pom.xml            # Root Maven aggregator POM
├── .gitignore         # Global ignore rules for every module
└── README.md
```

Each directory under `services/` is reserved for one independent Maven project (a microservice). The `.gitkeep` files keep those folders tracked in Git until the service code is added.

## Root pom.xml

The root `pom.xml` is the aggregator POM for the microservices monorepo. It uses `<packaging>pom</packaging>` and the coordinates `org.forafdate:foraf-date-parent:1.0.0-SNAPSHOT`, and it lets the whole platform be validated from the repository root with one command:

```bash
mvn validate
```

No `<modules>` are declared yet. A `<module>` entry is added to the root POM only when the corresponding service has its own `pom.xml`, so the reactor stays valid while services are being initialized.
