# Demo video script

[Back to README](README.md)

The narration and screen plan for the walkthrough video, about 6.5 minutes long and recorded against the
live Render deployment. Figures quoted in the narration are the values the live service returned
during recording. The JUnit suite has since grown from 1,122 to 1,148 tests.

## At a glance

| # | Time | Topic | Screen |
|---|---|---|---|
| 1 | 0:00 | Overview | README on GitHub |
| 2 | 0:42 | Request flow | `docs/architecture.html` |
| 3 | 1:29 | Greedy algorithm | `GreedyPriorityDispatchStrategy` and `CapacityAssigner` on GitHub |
| 4 | 2:17 | Live demo: upload and plan | Swagger UI on Render |
| 5 | 3:03 | Over-capacity edge case | Greedy plan response |
| 6 | 3:42 | Greedy vs OR-Tools | OR-Tools plan response |
| 7 | 4:46 | 5,000-order test data | Test-data endpoint, raw plan JSON |
| 8 | 5:40 | Tests and coverage | `mvnw clean verify` output, JaCoCo report |
| 9 | 6:18 | Close | "Deploy to Render" section |

## Before recording

- Call `/actuator/health` a minute ahead so a sleeping free instance has woken up.
- Call `GET /api/dispatch/plan?strategy=ortools` once, so the OR-Tools native library is already loaded.
- Reset the data with `DELETE /api/dispatch/orders` and `DELETE /api/dispatch/vehicles`, and keep this
  order: sample data first, test data last (test data replaces everything).
- Afterwards, reload the sample data so reviewers find a ready-to-plan dataset.

## Script

### 1. Overview (0:00)

**Screen:** README title and introduction; highlight "Capacitated Vehicle Routing Problem" and the live URL.

> Hi, this is my Dispatch Load Balancer for FreightFox. It's a Spring Boot 3.5 service on Java 21 that
> assigns delivery orders to a fleet of vehicles. This is a capacitated vehicle routing problem. No
> vehicle may carry more than its capacity. High-priority orders go first, then medium, then low. And
> the total distance, measured with the Haversine formula, should be as small as possible. It's a pure
> REST API, with Swagger docs and a Postman collection, and it's deployed live on Render.

### 2. Request flow (0:42)

**Screen:** the architecture diagram; mark the controller, services, strategy registry, the
"seed + fallback" link and the exception handler as they are mentioned.

> Here is the request flow. Controllers are thin: they validate the request and call a service. Uploads
> go through one shared chunked upserter. For every 1,000 rows, it runs one select, then batched inserts
> and updates. For a plan, the dispatch service picks a strategy from a registry of strategy beans, so
> there's no if-else on the strategy name. There are two strategies: greedy, and OR-Tools. OR-Tools
> starts from the greedy plan, and falls back to greedy when it can't, or shouldn't, run. Every error
> goes through one exception handler, so all errors share the same JSON shape.

### 3. Greedy algorithm (1:29)

**Screen:** the class Javadoc of `GreedyPriorityDispatchStrategy`, then the inner loop of
`CapacityAssigner.assign`.

> The default greedy algorithm sorts the orders by priority, then heavier first, then by order ID, so
> the result is deterministic. Each order goes to the vehicle that still has room, and whose route
> currently ends nearest to it. Ties go to the smaller vehicle ID. Because every high order is placed
> before any medium or low order, a lower priority can never take capacity from a high one. The
> assignment loop uses plain arrays and precomputed trigonometry, so it doesn't allocate. Then each route
> is improved with nearest neighbour and two-opt, within a fixed time budget. Routes are open, with no
> return trip.

### 4. Live demo: upload and plan (2:17)

**Screen:** Swagger UI. `POST /orders` and `POST /vehicles` with the "Sample dataset (Delhi NCR)"
example, then `GET /plan` with `strategy=greedy`.

> Now, the live service on Render, through Swagger. First, I upload the 30 sample orders around
> Connaught Place, Karol Bagh, Noida and Gurgaon. The response: delivery orders accepted. Then the 5
> sample vehicles. Both calls are upserts. Posting an existing ID updates it, and the whole batch is
> validated before anything is stored. Now I ask for the plan with the greedy strategy. Every vehicle is
> listed with its total load, its distance in kilometres, and its orders in visiting order. Vehicle 1 is
> loaded to exactly 100 out of 100.

### 5. Over-capacity edge case (3:03)

**Screen:** the `unassignedOrders` part of the greedy response.

> This sample is an intentional edge case. The orders weigh 645 kilograms in total, but the fleet can
> only carry 500. Greedy assigns all 12 high and all 8 medium orders, plus 2 low ones. The 8 low orders
> that don't fit come back in unassigned orders, each with a reason: insufficient remaining capacity
> across the fleet. Nothing fails, and every order is accounted for exactly once. The total distance for
> greedy is 73.42 kilometres.

### 6. Greedy vs OR-Tools (3:42)

**Screen:** `GET /plan` with `strategy=ortools`; the vehicle distances, the dropped orders and the
`strategy` field.

> Now, the same data with Google OR-Tools. It's modelled as a capacitated routing problem, where every
> order is optional, with a drop penalty. The penalties are ordered, so that dropping one high order
> costs more than dropping every medium and low order, plus any distance. The solver is warm-started from
> the greedy plan, so it never drops more high orders than greedy does. Then guided local search improves
> it for 3 seconds. On this run, OR-Tools found 48.27 kilometres, against 73.42 for greedy, and it served
> 24 orders instead of 22. All 12 high orders are still assigned, and the 6 orders it dropped are all low
> priority, each with the reason: dropped by optimizer, insufficient capacity. The strategy field at the
> bottom always says what actually ran.

### 7. 5,000-order test data (4:46)

**Screen:** `POST /test-data` with 5,000 orders and 100 vehicles; then the plan URL opened directly in
the browser (the response is almost 1 MB).

> For scale, this demo endpoint generates a reproducible dataset around 30 Indian cities, and replaces
> everything that's stored. Here it's 5,000 orders and 100 vehicles, stored through the same chunked
> upsert, in under 3 seconds. Then I open the greedy plan for all 5,000 orders directly, because it's
> almost a megabyte of JSON. The server returned it in under a second. Most orders are unassigned here on
> purpose: 100 vehicles carry about 30,000 kilograms, against about 127,000 kilograms of demand. The
> benchmarks in the performance document go up to 50,000 orders and 500 vehicles, which greedy plans in
> about 75 milliseconds.

### 8. Tests and coverage (5:40)

**Screen:** the end of a finished `./mvnw clean verify` run, then `target/site/jacoco/index.html`.

> The project was built test-first, with JUnit 5, Mockito, MockMvc and AssertJ. Maven verify runs 1,122
> tests, and enforces an 85 percent line-coverage gate with JaCoCo. Line coverage is at 98 percent.
> The tests cover every validation rule and exact error message, the high-priority rule on random data,
> the exact sample routes, every OR-Tools fallback, and they prove JDBC batching by counting the real SQL
> statements.

### 9. Close (6:18)

**Screen:** the "Deploy to Render" section.

> Everything is in the README: how to run it locally or with Docker, curl examples for every endpoint,
> the Postman collection, and the Render deployment, with Docker and a health check. Thanks for watching!
