# Running, configuration and deployment

[Back to README](../README.md)

How to run the service locally, with PostgreSQL or in Docker, how to configure it, and how it is built,
tested and deployed to production.

- [Environments](#environments)
- [Run locally](#run-locally)
- [Run with PostgreSQL](#run-with-postgresql)
- [Docker](#docker)
- [Database schema (Flyway)](#database-schema-flyway)
- [Configuration](#configuration)
- [Production on Render](#production-on-render)
- [CI/CD pipeline](#cicd-pipeline)

## Environments

| Environment | Database | How it is started |
|---|---|---|
| Local development and the test suite | In-memory H2 (PostgreSQL mode), so nothing to install | `./mvnw spring-boot:run`, `./mvnw verify` |
| PostgreSQL integration tests | PostgreSQL 16 in a throw-away container (Testcontainers) | `./mvnw verify` wherever Docker is available, always in CI |
| Production | PostgreSQL 16, Render managed database | `prod` profile, deployed from [`render.yaml`](../render.yaml) |

The same Flyway migrations create the schema in every environment, and Hibernate only validates it.

## Run locally

**Prerequisites:** JDK 21. Nothing else: Maven is downloaded by the wrapper on first use. All commands
are run from the project root.

```bash
./mvnw spring-boot:run          # macOS / Linux / Git Bash
.\mvnw.cmd spring-boot:run      # Windows (PowerShell or cmd)
```

Or build the jar and run it:

```bash
./mvnw clean package            # add -DskipTests to skip the tests
java -jar target/dispatch-load-balancer-1.0.0.jar
```

The service listens on port 8080 (or `$PORT` if set). Open http://localhost:8080 for Swagger UI.

## Run with PostgreSQL

Activate the `prod` profile and point it at a database, either with separate variables (the form Render
uses) or with one JDBC URL:

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_HOST=localhost DB_PORT=5432 DB_NAME=dispatch DB_USERNAME=dispatch DB_PASSWORD=secret
# or: export DB_URL="jdbc:postgresql://localhost:5432/dispatch?reWriteBatchedInserts=true"
java -jar target/dispatch-load-balancer-1.0.0.jar
```

PowerShell: `$env:SPRING_PROFILES_ACTIVE="prod"; $env:DB_HOST="localhost"; ...`. Flyway creates the tables
on the first start. `GET /actuator/info` confirms which database the service is connected to.

## Docker

```bash
docker build -t dispatch-load-balancer .
docker run -p 8080:8080 dispatch-load-balancer
```

The multi-stage `Dockerfile` builds with the Maven wrapper on `eclipse-temurin:21-jdk-jammy` and runs
on `eclipse-temurin:21-jre-jammy`, as a non-root user with `JAVA_OPTS=-XX:MaxRAMPercentage=75`. The
runtime image uses Ubuntu (glibc) because the OR-Tools native libraries need glibc; Alpine (musl) would
not work. The jar is about 182 MB because it bundles the OR-Tools native libraries for all platforms.
For PostgreSQL, add `-e SPRING_PROFILES_ACTIVE=prod -e DB_HOST=... -e DB_NAME=... -e DB_USERNAME=...
-e DB_PASSWORD=...`.

## Database schema (Flyway)

- **Migrations:** versioned SQL in
  [`src/main/resources/db/migration`](../src/main/resources/db/migration), applied automatically on
  start-up and recorded in `flyway_schema_history`.
- **V1** creates `vehicles` and `delivery_orders` with primary keys and `CHECK` constraints that mirror
  the API validation: positive weight and capacity, latitude -90 to 90, longitude -180 to 180, and a
  priority of HIGH, MEDIUM or LOW. The database therefore enforces the rules too, not just the API.
- **Schema changes** are always a new migration (`V2__...sql`); applied migrations are never edited.
  Hibernate runs with `ddl-auto: validate`, so the application refuses to start if the entities and the
  schema disagree.

## Configuration

All keys live in `src/main/resources/application.yml` and can be overridden with command-line
arguments (e.g. `--app.dispatch.ortools.time-limit=5s`) or environment variables using Spring's
relaxed binding (e.g. `APP_DISPATCH_ORTOOLS_TIMELIMIT=5s`).

| Key | Default | Meaning |
|---|---|---|
| `app.dispatch.upsert-chunk-size` | `1000` | Rows per upsert chunk (one `findAllById` + one `saveAll` per chunk) |
| `app.dispatch.greedy.two-opt-max-iterations` | `1000` | Maximum 2-opt improvement passes per vehicle |
| `app.dispatch.greedy.two-opt-time-budget` | `25ms` | Maximum 2-opt wall time per vehicle |
| `app.dispatch.ortools.time-limit` | `3s` | OR-Tools solver time limit |
| `app.dispatch.ortools.max-orders` | `1000` | Above this many orders, `ortools` falls back to greedy |
| `app.test-data.enabled` | `true` | Exposes `POST /api/dispatch/test-data` (404 when `false`) |
| `app.test-data.max-orders` | `50000` | Largest allowed `orders` parameter |
| `app.test-data.max-vehicles` | `2000` | Largest allowed `vehicles` parameter |

| Environment variable | Used for |
|---|---|
| `PORT` | Server port (default 8080); set by Render |
| `SPRING_PROFILES_ACTIVE` | `prod` for PostgreSQL |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection (`prod`); injected by Render from the managed database |
| `DB_URL` | Optional full JDBC URL; overrides the host fields |
| `JAVA_OPTS` | JVM flags in the Docker image |
| `RENDER_GIT_COMMIT` | Deployed commit, reported by `/actuator/info`; set by Render |

The `prod` profile also sizes the HikariCP pool for the free tier (5 connections) and enables
`reWriteBatchedInserts`, so the driver sends JDBC batches as multi-row `INSERT`s.

## Production on Render

[`render.yaml`](../render.yaml) is a Render Blueprint describing the whole production setup:

| Resource | Configuration |
|---|---|
| `dispatch-db` | PostgreSQL 16, free plan, region Singapore |
| `dispatch-load-balancer` | Docker web service, free plan, region Singapore, health check `/actuator/health`, `prod` profile |

The service's database variables are declared with `fromDatabase`, so Render injects the host, port,
database name, user and password itself. No credential is stored in the repository or typed by hand.

**First-time setup**
1. In the Render dashboard choose **New > Blueprint**, select the repository and apply it. Render creates
   the database and the web service.
2. On the web service's **Settings** page, copy the **Deploy Hook** URL.
3. Add the URL to the GitHub repository as the secret `RENDER_DEPLOY_HOOK_URL`, under Settings > Secrets
   and variables > Actions. From then on, CI deploys every change that passes.

**Verify a deployment**
- `/actuator/health` returns `{"status":"UP"}`; Render also uses this endpoint as its health check.
- `/actuator/info` shows the deployed `commit` and `"database": {"product": "PostgreSQL", ...}`.
- The Postman collection passes against the live URL.

**Notes**
- **Resources:** the free web service has 512 MB RAM (heap about 384 MB) and a 0.1 share of one CPU.
  Large uploads therefore take seconds rather than milliseconds; see [PERFORMANCE.md](PERFORMANCE.md).
- **Sleep:** the free web service sleeps after about 15 idle minutes, and the first request afterwards
  can take up to about 2 minutes. Stored data is not affected.
- **Database expiry:** Render deletes free PostgreSQL databases 30 days after creation. This one expires
  on 13 December 2026, unless it is moved to a paid plan.
- **Failed deploys are safe:** a new version only replaces the running one after it passes the health
  check. A deploy that fails to start leaves the previous version live.

## CI/CD pipeline

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) has three jobs:

| Job | When | What it does |
|---|---|---|
| Build and test | Every push and pull request | `./mvnw verify`: all tests on H2, the PostgreSQL integration tests on a real PostgreSQL 16 (Testcontainers), and the 85% coverage gate; uploads test and coverage reports |
| Deploy to Render | Pushes to `main`, after the build passes | Calls the Render deploy hook, then waits until `/actuator/info` reports the new commit |
| Verify the live deployment | After the deploy | Runs the Postman collection against the live server with newman; uploads the results |

A change reaches production only after every test has passed, and every deployment is verified against
the live server.

**Current status:** GitHub has placed a billing lock on the repository owner's account, so GitHub Actions
does not start workflow runs at the moment. This is awaiting GitHub Support. Until it is lifted, the same
steps are run by hand:
1. `./mvnw verify`.
2. **Manual Deploy > Deploy latest commit** in Render.
3. The Postman collection against the live server.
