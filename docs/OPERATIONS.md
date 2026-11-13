# Running, configuration and deployment

[Back to README](../README.md)

- [Run locally](#run-locally)
- [PostgreSQL](#postgresql-prod-profile)
- [Docker](#docker)
- [Configuration](#configuration)
- [Deploy to Render](#deploy-to-render)

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

The service listens on port 8080 (or `$PORT` if set). Open http://localhost:8080/swagger-ui.html.

## PostgreSQL (`prod` profile)

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL="jdbc:postgresql://localhost:5432/dispatch?reWriteBatchedInserts=true"
export DB_USERNAME=dispatch
export DB_PASSWORD=secret
java -jar target/dispatch-load-balancer-1.0.0.jar
```

PowerShell: `$env:SPRING_PROFILES_ACTIVE="prod"; $env:DB_URL="jdbc:postgresql://..."; ...`. The `prod`
profile uses `ddl-auto: update`, so the tables are created on first start.

## Docker

```bash
docker build -t dispatch-load-balancer .
docker run -p 8080:8080 dispatch-load-balancer
```

The multi-stage `Dockerfile` builds with the Maven wrapper on `eclipse-temurin:21-jdk-jammy` and runs
on `eclipse-temurin:21-jre-jammy` (glibc, required by the OR-Tools native libraries; Alpine/musl would
not work) as a non-root user with `JAVA_OPTS=-XX:MaxRAMPercentage=75`. The jar is about 182 MB because
it bundles the OR-Tools native libraries for all platforms. For PostgreSQL, add
`-e SPRING_PROFILES_ACTIVE=prod -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=...`.

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

Environment variables used by the deployment: `PORT` (server port, default 8080),
`SPRING_PROFILES_ACTIVE` (`prod` for PostgreSQL), `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (`prod` only),
`JAVA_OPTS` (JVM flags in the Docker image).

## Deploy to Render

The repository contains a `render.yaml` Blueprint (web service, Docker runtime, free plan, region
Singapore, health check `/actuator/health`, `JAVA_OPTS=-XX:MaxRAMPercentage=75`). The live instance is
https://dispatch-load-balancer-yl5j.onrender.com.

1. Push the repository to GitHub (the `Dockerfile` and `render.yaml` are at the root).
2. In the Render dashboard choose **New > Blueprint**, select the repository and apply it. Render reads
   `render.yaml` and creates the `dispatch-load-balancer` web service. (Without the Blueprint:
   **New > Web Service**, **Docker** runtime, plan **Free**, health check path `/actuator/health`.)
3. The first build runs the Maven build inside Docker; later builds reuse the cached dependency layer
   while `pom.xml` is unchanged.
4. No port configuration is needed: Render injects `PORT` and the application binds to it
   (`server.port=${PORT:8080}`).
5. Verify: `/actuator/health` returns `{"status":"UP"}` and `/swagger-ui.html` opens Swagger UI; "Try
   it out" calls the same host because the OpenAPI server URL is relative.

Notes:
- **Memory:** the free plan has 512 MB RAM; with `MaxRAMPercentage=75` the heap is about 384 MB. A
  5,000-order test-data demo is comfortable; 50,000-order demos may be memory-tight there.
- **Data resets:** the default profile uses in-memory H2, so all orders and vehicles are lost on every
  restart and redeploy.
- **Sleep:** free instances spin down after about 15 idle minutes; the first request afterwards takes
  about 50 s while the service starts again. Call `/actuator/health` before a demo.
- **Optional PostgreSQL:** create a Render PostgreSQL database and add `SPRING_PROFILES_ACTIVE=prod`,
  `DB_URL` (JDBC form, `jdbc:postgresql://<host>:<port>/<database>`), `DB_USERNAME` and `DB_PASSWORD`
  to the web service. Render shows the URL in `postgres://` form; convert it to the JDBC form and pass
  the user and password separately.
