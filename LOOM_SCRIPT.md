# Demo video script

[Back to README](README.md)

The narration and screen plan of the [walkthrough video](https://www.loom.com/share/a176871b7c3e417381f57ec08311f50f), 7:35 long and recorded against the live
Render deployment (PostgreSQL 16). The narration below is the exact audio of the video; figures quoted
in it are the values the live service and the build returned during recording.

## At a glance

| # | Time | Topic | Screen |
|---|---|---|---|
| 1 | 0:00 | Overview | README on GitHub |
| 2 | 0:48 | Request flow | `docs/architecture.html` |
| 3 | 1:50 | Greedy algorithm | `GreedyPriorityDispatchStrategy` and `CapacityAssigner` on GitHub |
| 4 | 2:38 | Live demo: upload and plan | Swagger UI on Render |
| 5 | 3:36 | Over-capacity edge case | Greedy plan response |
| 6 | 4:15 | Greedy vs OR-Tools | OR-Tools plan response |
| 7 | 5:19 | 5,000-order test data | Test-data endpoint, raw plan JSON |
| 8 | 6:12 | Tests and coverage | `mvnw clean verify` output, JaCoCo report |
| 9 | 7:04 | Close | README: How to evaluate, Deployment |

## Before recording

- Call `/actuator/health` a minute ahead so a sleeping free instance has woken up.
- Call `GET /api/dispatch/plan?strategy=ortools` once, so the OR-Tools native library is already loaded.
- Reset the data with `DELETE /api/dispatch/orders` and `DELETE /api/dispatch/vehicles`, and keep this
  order: sample data first, test data last (test data replaces everything).
- Afterwards, reload the sample data so reviewers find a ready-to-plan dataset.

## Script

### 1. Overview (0:00)

**Screen:** README title, the PostgreSQL badge, the Overview bullets (CVRP, capacity, priority,
Haversine), the live URL and the Postman badge.

> Hi, this is my Dispatch Load Balancer for FreightFox. It's a Spring Boot 3.5 service on Java 21,
> backed by PostgreSQL 16, that assigns delivery orders to a fleet of vehicles. This is a capacitated
> vehicle routing problem. Capacity is a hard constraint: no vehicle is ever loaded beyond it. High-
> priority orders go first, then medium, then low. And distance, measured with the Haversine formula, is
> minimised. It's a pure REST API, deployed live on Render, with Swagger docs, and a Postman suite of
> 398 assertions that passes against the live server.

### 2. Request flow (0:48)

**Screen:** The architecture diagram: controller, services, PostgreSQL and Flyway,
`DispatchSnapshotReader` (one REPEATABLE READ transaction), strategy registry, the "seed + fallback"
link and the exception handler, each marked as it is mentioned.

> Here is the request flow. Controllers are thin: they validate the request and call a service. Uploads
> go through one shared chunked upserter. For every 1,000 rows, it runs one select, then batched inserts
> and updates, into PostgreSQL, where Flyway owns the schema. For a plan, a snapshot reader loads the
> vehicles and the orders in one read-only, repeatable-read transaction, so a plan never sees half of an
> upload. Then the dispatch service picks a strategy from a registry of strategy beans, so there's no
> if-else on the strategy name. There are two strategies: greedy, and OR-Tools. OR-Tools starts from the
> greedy plan, and falls back to greedy when it can't, or shouldn't, run. Every error goes through one
> exception handler, so all errors share the same JSON shape.

### 3. Greedy algorithm (1:50)

**Screen:** The class Javadoc of `GreedyPriorityDispatchStrategy`, then the inner loop of
`CapacityAssigner.assign`.

> The default greedy algorithm sorts the orders by priority, then heavier first, then by order ID, so
> the result is deterministic. Each order goes to the vehicle that still has room, and whose route
> currently ends nearest to it. Ties go to the smaller vehicle ID. Because every high order is placed
> before any medium or low order, a lower priority can never take capacity from a high one. The
> assignment loop uses plain arrays and precomputed trigonometry, so it doesn't allocate. Then each
> route is improved with nearest neighbour and two-opt, within a fixed time budget. Routes are open,
> with no return trip.

### 4. Live demo: upload and plan (2:38)

**Screen:** Swagger UI groups (Dispatch, Reset, Test data); `POST /orders` and `POST /vehicles` with the
"Sample dataset (Delhi NCR)" example, then `GET /plan` with `strategy=greedy`.

> Now, the live service on Render, through Swagger. The groups follow the order you use the API:
> dispatch, then reset, then test data. First, I upload the 30 sample orders around Connaught Place,
> Karol Bagh, Noida and Gurgaon. The response: delivery orders accepted. Then the 5 sample vehicles.
> Both calls are upserts. Posting an existing ID updates it, and the whole batch is validated before
> anything is stored. Now I ask for the plan with the greedy strategy. Every vehicle is listed with its
> total load, its distance in kilometres, and its orders in visiting order. Vehicle 1 is loaded to
> exactly 100 out of 100.

### 5. Over-capacity edge case (3:36)

**Screen:** The `unassignedOrders` part of the greedy response.

> This sample is an intentional edge case. The orders weigh 645 kilograms in total, but the fleet can
> only carry 500. Greedy assigns all 12 high and all 8 medium orders, plus 2 low ones. The 8 low orders
> that don't fit come back in unassigned orders, each with a reason: insufficient remaining capacity
> across the fleet. Nothing fails, and every order is accounted for exactly once. The total distance for
> greedy is 73.42 kilometres.

### 6. Greedy vs OR-Tools (4:15)

**Screen:** `GET /plan` with `strategy=ortools`; the vehicle distances, the dropped orders and the
`strategy` field.

> Now, the same data with Google OR-Tools. It's modelled as a capacitated routing problem, where every
> order is optional, with a drop penalty. The penalties are ordered, so that dropping one high order
> costs more than dropping every medium and low order, plus any distance. The solver is warm-started
> from the greedy plan, so it never drops more high orders than greedy does. Then guided local search
> improves it for 3 seconds. On this run, OR-Tools found 48.27 kilometres, against 73.42 for greedy, and
> it served 24 orders instead of 22. All 12 high orders are still assigned, and the 6 orders it dropped
> are all low priority, each with the reason: dropped by optimizer, insufficient capacity. The strategy
> field at the bottom always says what actually ran.

### 7. 5,000-order test data (5:19)

**Screen:** `POST /test-data` with 5,000 orders and 100 vehicles; then the plan URL opened directly in
the browser (the response is almost 1 MB).

> For scale, this demo endpoint generates a reproducible dataset around 30 Indian cities, and replaces
> everything that's stored. Here it's 5,000 orders and 100 vehicles, written to PostgreSQL through the
> same chunked upsert, in a couple of seconds. Then I open the greedy plan for all 5,000 orders
> directly, because it's almost a megabyte of JSON. Most orders are unassigned here on purpose: 100
> vehicles carry about 30,000 kilograms, against about 127,000 kilograms of demand. On the live server,
> the Postman suite uploads 20,000 orders in a single request, and greedy plans them in a few seconds,
> on a free instance with a tenth of a CPU.

### 8. Tests and coverage (6:12)

**Screen:** The end of a real `./mvnw clean verify` run (1,148 tests, coverage gate met), then
`target/site/jacoco/index.html`.

> The project was built test-first, with JUnit 5, Mockito, MockMvc and AssertJ. Maven verify runs 1,148
> tests. Every Spring test runs against a real PostgreSQL 16 that Testcontainers starts for the build,
> so the tests use the production database engine. The build enforces an 85 percent line-coverage gate
> with JaCoCo, and line coverage is at 98 percent. The tests cover every validation rule and exact error
> message, the high-priority rule on random data, the exact sample routes, every OR-Tools fallback, a
> plan that stays consistent while an upload commits, and they prove JDBC batching by counting the real
> SQL statements.

### 9. Close (7:04)

**Screen:** The "How to evaluate" section (74 Postman requests, independent plan checks), then
"Deployment" (`render.yaml`, Flyway, the health check).

> Everything is in the README. The Postman collection runs 74 requests against the live server, and
> checks every plan independently: capacity, priority, and Haversine distance. The deployment is
> infrastructure as code: render.yaml declares the PostgreSQL database and the Docker web service,
> Flyway creates the schema, and a version only goes live after it passes the health check. Thanks for
> watching!
