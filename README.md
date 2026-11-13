# Dispatch Load Balancer

**A Spring Boot service that turns delivery orders and a vehicle fleet into an optimized,
capacity-safe, priority-aware dispatch plan.**

![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![OR-Tools](https://img.shields.io/badge/Google%20OR--Tools-9.15-4285F4?logo=google&logoColor=white)
![Tests](https://img.shields.io/badge/tests-1%2C122%20passing-brightgreen)
![Coverage](https://img.shields.io/badge/line%20coverage-97.9%25-brightgreen)
![Deployed on Render](https://img.shields.io/badge/deployed-Render-46E3B7?logo=render&logoColor=white)

| Resource | Link |
|---|---|
| **Live API** | https://dispatch-load-balancer-yl5j.onrender.com |
| **Swagger UI** | https://dispatch-load-balancer-yl5j.onrender.com/swagger-ui.html |
| **Video walkthrough** | _Loom link to be added_ |
| **Postman collection** | [`postman/DispatchLoadBalancer.postman_collection.json`](postman/DispatchLoadBalancer.postman_collection.json) |
| **Architecture diagram** | [`docs/architecture.html`](docs/architecture.html) (interactive) |

> The live instance runs on Render's free plan: after about 15 idle minutes the first request takes
> about 50 s while it wakes up. Data is held in memory, so load the sample data first (see
> [Quick start](#quick-start)).

## Contents

- [Overview](#overview)
- [Highlights](#highlights)
- [Quick start](#quick-start)
- [Architecture](#architecture)
- [API at a glance](#api-at-a-glance)
- [How dispatching works](#how-dispatching-works)
- [Results on the sample dataset](#results-on-the-sample-dataset)
- [Quality and performance](#quality-and-performance)
- [Tech stack](#tech-stack)
- [Project structure](#project-structure)
- [Assumptions](#assumptions)
- [Known limitations](#known-limitations)
- [Further documentation](#further-documentation)

## Overview

The service solves a **Capacitated Vehicle Routing Problem (CVRP)** through a REST API:

- **Capacity is a hard constraint.** No vehicle is ever loaded beyond its capacity.
- **Priority is respected.** HIGH orders are placed before MEDIUM, and MEDIUM before LOW.
- **Distance is minimized.** Distances use the Haversine formula, and routes are open (no return trip).
- **Nothing is silently lost.** Orders that cannot fit are returned in `unassignedOrders` with a reason
  instead of failing the request.

Two interchangeable algorithms are available per request: a fast, deterministic **greedy** heuristic
(the default) and a **Google OR-Tools** CVRP solver (`?strategy=ortools`).

## Highlights

- **Two strategies behind one contract.** A registry built from strategy beans picks the algorithm by
  name, with no if/else. OR-Tools is warm-started from the greedy plan, guarded so it can never drop
  more high-priority orders than greedy, and falls back to greedy instead of failing.
- **Fast at scale.** Greedy plans **50,000 orders x 500 vehicles in 75 ms** with an allocation-free
  O(n x m) inner loop. A 50,000-order request body is accepted and stored in one call.
- **Efficient writes.** Chunked upsert with one `SELECT` per 1,000 rows and JDBC-batched
  inserts/updates, proven by tests that count the real SQL statements.
- **Strict, consistent API.** Bean Validation plus strict JSON parsing, one error shape for every
  failure, and no stack traces leaked. Concurrent duplicate inserts return 409, never 500.
- **Thoroughly tested.** 1,122 tests, **97.9% line coverage** (85% gate enforced by the build),
  benchmarks against CVRPLIB instances, and 119 Postman assertions.
- **Production-ready packaging.** Multi-stage, non-root Docker image, a Render Blueprint with health
  check, H2 by default and a PostgreSQL profile.

## Quick start

Requires **JDK 21** only; the Maven wrapper downloads everything else.

```bash
./mvnw spring-boot:run            # Windows: .\mvnw.cmd spring-boot:run
```

Then, from the project root (use `https://dispatch-load-balancer-yl5j.onrender.com` instead of
`localhost:8080` to try the live deployment):

```bash
curl -X POST localhost:8080/api/dispatch/orders   -H "Content-Type: application/json" -d @samples/orders.json
curl -X POST localhost:8080/api/dispatch/vehicles -H "Content-Type: application/json" -d @samples/vehicles.json
curl "localhost:8080/api/dispatch/plan"                     # greedy (default)
curl "localhost:8080/api/dispatch/plan?strategy=ortools"    # OR-Tools
```

Or open http://localhost:8080/swagger-ui.html and use the preloaded "Sample dataset (Delhi NCR)"
examples. Docker: `docker build -t dispatch-load-balancer . && docker run -p 8080:8080 dispatch-load-balancer`.

## Architecture

[![Request flow](docs/architecture.png)](docs/architecture.html)

The architecture is layered:
- **Controllers** are thin: they validate the request DTOs and delegate to services.
- **Uploads** go through one shared chunked upserter.
- **Plans** are built by `DispatchService`, which resolves a strategy from the registry, loads vehicles
  and orders, runs the algorithm outside any transaction and maps the result to a response DTO.
- **Errors** from anywhere pass through a single `@RestControllerAdvice`, and JPA entities never leave
  the service layer.

The interactive diagram ([`docs/architecture.html`](docs/architecture.html), light/dark theme) traces
every path. The component view, package layout and the full list of design patterns are in
[docs/DESIGN.md](docs/DESIGN.md).

## API at a glance

Base path `/api/dispatch`, JSON in and out.

| Endpoint | Purpose |
|---|---|
| `POST /orders` | Upsert delivery orders by `orderId` (`{"orders": [...]}`) |
| `POST /vehicles` | Upsert vehicles by `vehicleId` (`{"vehicles": [...]}`) |
| `GET /plan?strategy=greedy\|ortools` | Compute the dispatch plan (`greedy` by default) |
| `DELETE /orders`, `DELETE /vehicles` | Clear stored data |
| `POST /test-data?orders=&vehicles=&seed=` | Generate a reproducible dataset around 30 Indian cities (demo) |
| `GET /actuator/health` | Health check |

A plan lists every vehicle with its load, distance and orders in visiting order, plus any unassigned
orders with a reason:

```json
{
  "dispatchPlan": [
    { "vehicleId": "VEH001", "totalLoad": 100, "totalDistance": "14.44 km",
      "assignedOrders": [ { "orderId": "ORD026", "...": "...", "priority": "HIGH" } ] }
  ],
  "unassignedOrders": [
    { "orderId": "ORD003", "...": "...", "priority": "LOW",
      "reason": "Insufficient remaining capacity across fleet" }
  ],
  "strategy": "greedy"
}
```

Every error has the same shape, for example:

```json
{"status": "error", "message": "Validation failed",
 "errors": ["orders[0].latitude: must be less than or equal to 90.0"], "timestamp": "2026-11-12T10:15:30.123Z"}
```

Field rules, status codes and copy-paste curl examples for every success and error case are in
[docs/API.md](docs/API.md).

## How dispatching works

**Greedy (default).** The steps are:
1. Sort orders by priority, then heavier first, then `orderId`.
2. Give each order to the vehicle with room whose route currently ends nearest to it; ties go to the
   smaller `vehicleId`.
3. Improve each route with nearest-neighbour and 2-opt, capped at 1,000 passes and 25 ms per vehicle.

Because every HIGH order is placed before any MEDIUM or LOW order, a lower priority can never take
capacity from a higher one. The algorithm is deterministic, and the HIGH-first rule is verified on
randomized data.

**OR-Tools.** The model is built as follows:
- **Open routes.** There is one start node per vehicle, plus a shared zero-cost end node.
- **Capacity.** A capacity dimension enforces each vehicle's limit.
- **Lexicographic drop penalties.** Dropping one HIGH order costs more than dropping every MEDIUM and
  LOW order plus any distance.

The search starts from the greedy plan and runs Guided Local Search for 3 s. If it would leave more
high-priority orders unassigned than greedy, the greedy plan is returned. The response falls back to
greedy, and says so in `strategy`, in these cases:
- more than 1,000 orders;
- the native library is unavailable;
- two solves are already running;
- the solver fails.

The full description, including the penalty formulas and the complexity analysis, is in
[docs/ALGORITHMS.md](docs/ALGORITHMS.md).

## Results on the sample dataset

`samples/` holds a Delhi NCR dataset with 30 orders and 5 vehicles. It is an intentional over-capacity
case: the orders weigh **645 kg**, but the fleet carries only **500 kg**.

| | Greedy | OR-Tools (3 s) |
|---|---|---|
| Orders assigned | 22 of 30 | 24 of 30 |
| HIGH / MEDIUM assigned | 12 of 12 / 8 of 8 | 12 of 12 / 8 of 8 |
| Unassigned (all LOW) | 8 | 6 |
| Total distance | **73.42 km** (deterministic) | **34.24 km** on a 16-core machine, **48.27 km** on Render's free instance |
| Capacity respected | Yes, every vehicle | Yes, every vehicle |

Both runs are asserted end to end by `SampleDatasetIntegrationTest`: every HIGH order assigned, no
vehicle over capacity, all 30 orders accounted for, and OR-Tools never longer than greedy.
Vehicle-by-vehicle routes are in [docs/ALGORITHMS.md](docs/ALGORITHMS.md#sample-dataset-walk-through).

## Quality and performance

| Tests | Line coverage | Branch coverage | Build gate |
|---|---|---|---|
| **1,122** passing | **97.87%** | **87.76%** | `./mvnw verify` fails below 85% line coverage |

The tests cover:
- every validation rule and exact error message;
- the HIGH-priority invariant on random data;
- the exact sample routes;
- every OR-Tools fallback;
- the concurrency bulkhead;
- JDBC batching, proven by counting the actual SQL statements.

See [docs/TESTING.md](docs/TESTING.md).

Benchmarks (`./mvnw verify -Pperformance`, generated into [docs/PERFORMANCE.md](docs/PERFORMANCE.md)):

| Strategy | Orders x Vehicles | Upsert | Plan | Peak heap |
|---|---|---:|---:|---:|
| greedy | 10,000 x 200 | 274 ms | 12 ms | 146 MB |
| greedy | 50,000 x 500 | 863 ms | **75 ms** | 344 MB |
| ortools | 200 x 20 | 8 ms | 3,012 ms | 50 MB |
| ortools | 1,000 x 50 | 13 ms | 3,089 ms | 65 MB |

On the same data, OR-Tools serves the same orders per priority as greedy with **60.4% less distance**
at 200 x 20 and **9.2% less** at 1,000 x 50. On CVRPLIB set X instances, OR-Tools (3 s) is within
11–16.5% of the best-known solutions, compared with 36–46% for greedy.

## Tech stack

| Area | Technology |
|---|---|
| Language and framework | Java 21, Spring Boot 3.5.16 (Web, Data JPA, Validation, Actuator) |
| Optimization | Google OR-Tools 9.15 (routing solver, native libraries loaded lazily) |
| Persistence | Hibernate 6.6, H2 in-memory by default, PostgreSQL via the `prod` profile |
| API documentation | springdoc-openapi 2.10 (Swagger UI) |
| Testing | JUnit 5, Mockito, MockMvc, AssertJ, JaCoCo 0.8.16 |
| Build and delivery | Maven Wrapper, multi-stage Docker image (`eclipse-temurin:21-jre-jammy`), Render Blueprint |

## Project structure

```
src/main/java/com/freightfox/dispatch
  controller/    REST controllers, API constants, Swagger examples
  service/       Order, vehicle and dispatch services; ChunkedUpserter
  strategy/      DispatchStrategy contract
    greedy/      Priority-first greedy + nearest-neighbour/2-opt route improvement
    ortools/     CVRP model builder, drop penalties, solver adapter, lazy native loader
    registry/    Strategy lookup by name
  distance/      Haversine calculator and precomputed trigonometry table
  dto/, mapper/  Request/response records and mappers (entities never leak into the API)
  entity/, repository/   JPA entities and Spring Data repositories
  exception/     GlobalExceptionHandler and error translation
  testdata/      Seeded generator for 30 Indian cities
samples/         Ready-to-use request bodies (Delhi NCR dataset)
postman/         Postman collection with assertions
docs/            API, algorithms, design, operations, testing, performance, architecture diagram
Dockerfile, render.yaml
```

## Assumptions

- **Routes:** routes are open (they end at the last stop); distances are great-circle (Haversine,
  R = 6371 km), not road distances; there are no time windows.
- **Units:** weights and capacities are whole numbers, such as kg. `10.5` is rejected rather than
  truncated.
- **POST behaviour:** each POST is an all-or-nothing upsert. The whole batch is validated first; existing
  IDs are updated, new IDs are inserted, and IDs not in the request are left untouched.
- **Priority input:** `priority` is case-insensitive; numeric values are rejected.
- **Plans:** a plan is computed on demand and never stored. An unknown strategy or an empty fleet
  returns 400.
- **Unassigned orders:** they are reported, never dropped silently.

The full list of decisions is in [docs/DESIGN.md](docs/DESIGN.md#assumptions-and-decisions).

## Known limitations

- **OR-Tools results can vary.** The search is time-limited (about 3 s per plan), so results can differ
  slightly between runs and machines. Constraints and priorities always hold.
- **OR-Tools scope is capped.** It handles up to 1,000 orders and 2 concurrent solves; beyond that the
  service uses greedy and reports `"strategy": "greedy"`.
- **Greedy is a heuristic.** It guarantees capacity and priority order, but not minimum distance or
  maximum orders served.
- **Data does not persist by default.** H2 is in memory, so data is lost on restart unless the `prod`
  PostgreSQL profile is used. Render's free plan (512 MB) suits demos of up to about 5,000 orders.
- **Request size is not capped in the application.** Put a body-size limit in a reverse proxy for public
  exposure.

## Further documentation

| Document | Contents |
|---|---|
| [docs/API.md](docs/API.md) | Endpoints, field rules, response and error formats, status codes, curl examples |
| [docs/ALGORITHMS.md](docs/ALGORITHMS.md) | Haversine, greedy, OR-Tools model and penalties, fallbacks, sample walk-through |
| [docs/DESIGN.md](docs/DESIGN.md) | Component diagram, package layout, design patterns, assumptions and decisions, scaling |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Running locally, PostgreSQL, Docker, configuration, Render deployment |
| [docs/TESTING.md](docs/TESTING.md) | Test commands, coverage, what each test area proves |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | Full benchmark results and CVRPLIB comparison |
