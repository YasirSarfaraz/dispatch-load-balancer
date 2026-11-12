# Loom demo script (about 5 minutes)

Dispatch Load Balancer: a Spring Boot service for the Capacitated Vehicle Routing Problem.

## Before recording

- Start the app (locally with `./mvnw spring-boot:run` / `.\mvnw.cmd spring-boot:run`, or use the
  Render URL). On Render, call `/actuator/health` a minute before recording so a sleeping free
  instance has woken up.
- Warm up OR-Tools once (the first call loads the native library, about 0.9 s extra): load the sample
  data, call `GET /api/dispatch/plan?strategy=ortools`, then reset with `DELETE /api/dispatch/orders`
  and `DELETE /api/dispatch/vehicles`.
- Open these tabs: Swagger UI (`<base-url>/swagger-ui.html`), the README rendered on GitHub (for the
  Mermaid diagram), the IDE on `GreedyPriorityDispatchStrategy.java`, a terminal with a finished
  `./mvnw verify` run (do not run it live; it takes too long), and `target/site/jacoco/index.html`.
- Open the browser DevTools Network tab next to Swagger UI (for the response-time segment).
- Keep the order: sample data first, test data last (test data REPLACES all orders and vehicles).

## 0:00 - 0:30 Intro

**Screen:** README top section (title, overview, Live URL / Swagger / Loom placeholders filled in).

**Do:** Scroll slowly over the overview.

**Say:** "Hi, this is my Dispatch Load Balancer for FreightFox. It is a Spring Boot 3.5 service on Java 21
that assigns delivery orders to a vehicle fleet. It is a Capacitated Vehicle Routing Problem: no
vehicle may exceed its capacity, HIGH priority goes first, then MEDIUM, then LOW, and total distance is
minimized using the Haversine formula. It is a pure REST API with Swagger docs, a Postman collection,
and it is deployed on Render."

## 0:30 - 1:00 Architecture

**Screen:** README "Architecture" Mermaid diagram, then the package tree.

**Do:** Point at controller, services, registry, the two strategies, repositories, exception handler.

**Say:** "Controllers are thin: they validate and map DTOs, then call services. The upsert goes through
one shared ChunkedUpserter: per 1,000 rows one SELECT, then JDBC-batched inserts and updates. For the
plan, the DispatchService resolves a strategy from a registry built from every DispatchStrategy bean,
so there is no if/else on the strategy name. There are two strategies, greedy and OR-Tools, and
OR-Tools falls back to greedy if it can't or shouldn't run. One RestControllerAdvice gives every error
the same JSON shape, and entities never leak into the API."

## 1:00 - 1:40 Greedy algorithm

**Screen:** IDE, `GreedyPriorityDispatchStrategy.java` class Javadoc, then `CapacityAssigner.assign`.

**Do:** Highlight the numbered steps in the Javadoc, then the inner loop over vehicles.

**Say:** "The default greedy algorithm sorts orders by priority, then heavier first, then orderId, so it
is deterministic. Each order goes to the vehicle with enough remaining capacity whose current route end
is nearest; ties go to the smaller vehicleId. Because all HIGH orders are placed before any MEDIUM or
LOW, a lower priority can never take capacity from a HIGH order. That invariant is tested on
randomized data. Assignment is O(n times m) on primitive arrays with precomputed trigonometry, so the
inner loop doesn't allocate. Then each route is improved with nearest-neighbour and 2-opt, capped at
1,000 passes and 25 milliseconds per vehicle. Routes are open: no return trip."

## 1:40 - 2:40 Live Swagger demo with the sample data

**Screen:** Swagger UI, tag "Dispatch".

**Do:**
1. Expand **POST /api/dispatch/orders** ("Upload delivery orders (upsert)"), click **Try it out**,
   choose the example **"Sample dataset (Delhi NCR)"** in the Examples dropdown, click **Execute**.
   Show the response: `{"message": "Delivery orders accepted.", "status": "success"}`.
2. Expand **POST /api/dispatch/vehicles** ("Upload vehicles (upsert)"), **Try it out**, example
   **"Sample dataset (Delhi NCR)"**, **Execute**. Response: `{"message": "Vehicle details accepted.",
   "status": "success"}`.
3. Expand **GET /api/dispatch/plan** ("Compute the dispatch plan"), **Try it out**, leave `strategy`
   at `greedy`, **Execute**. Scroll through `dispatchPlan`.

**Say:** "These are the 30 sample orders across Connaught Place, Karol Bagh, Noida and Gurgaon, and 5
vehicles. Both POSTs are upserts: posting an existing ID updates it, and a whole batch is validated
before anything is stored. Now the plan: every vehicle is listed with its total load, its distance as a
string with two decimals, and its orders in visiting order. VEH001 is loaded exactly to 100 out of 100.
VEH002 starts in Noida and takes three Noida orders, so its distance is 0.00 km."

Optional (10 seconds): in **POST /api/dispatch/orders** change one priority to `"URGENT"` and
Execute to show the 400: `"Invalid value 'URGENT' for field 'orders[0].priority'. Allowed values:
HIGH, MEDIUM, LOW"`. Then restore the sample example.

## 2:40 - 3:10 The over-capacity case

**Screen:** The same greedy plan response, scrolled to `unassignedOrders`.

**Do:** Point at the eight entries and their `reason`.

**Say:** "This dataset is an intentional edge case: the orders weigh 645 but the fleet only carries
500. Greedy assigns all 12 HIGH and all 8 MEDIUM orders plus 2 LOW, and these 8 LOW orders, ORD003,
014, 016, 019, 022, 024, 028 and 030, come back in unassignedOrders with the reason 'Insufficient
remaining capacity across fleet'. An order heavier than every vehicle would get 'Package weight
exceeds capacity of every vehicle'. Nothing fails; every order is accounted for exactly once."

## 3:10 - 3:50 Greedy vs OR-Tools

**Screen:** Swagger **GET /api/dispatch/plan**, then README "Sample dataset walk-through" table.

**Do:** Set `strategy` to `ortools`, **Execute** (it takes about 3 seconds). Show `"strategy":
"ortools"` at the bottom and the `unassignedOrders` reasons. Then show the README comparison.

**Say:** "Now the same data with Google OR-Tools. It is modelled as a CVRP with one start node per
vehicle and one shared dummy end node with zero cost, which gives open routes. Each order is optional
with a drop penalty, and the penalties are lexicographic: dropping one HIGH costs more than dropping
every MEDIUM and LOW order plus any distance, and so on down. The solver is warm-started from the
greedy plan, so it can never drop more HIGH orders than greedy, and guided local search then improves
it within a 3-second limit. On this sample greedy needs 73.42 km; OR-Tools found 34.24 km on
my machine and serves 24 orders instead of 22, dropping 6 LOW orders with 'Dropped by optimizer:
insufficient capacity'. The time-limited search can vary slightly between runs, but the constraints
always hold. The strategy field tells you what actually ran: above 1,000 orders, or if the native
library fails, it falls back to greedy and says 'greedy'."

## 3:50 - 4:25 5,000-order test data and response time

**Screen:** Swagger tag **"Test data (demo)"**, DevTools Network tab visible.

**Do:**
1. Expand **POST /api/dispatch/test-data** ("Generate test data (demo; replaces all orders and
   vehicles)"), **Try it out**, enter `orders` = `5000`, `vehicles` = `100`, `seed` = `42`,
   **Execute**. Point at `ordersGenerated`, `vehiclesGenerated` and `elapsedMs` in the response.
   (Equivalent request: `POST /api/dispatch/test-data?orders=5000&vehicles=100&seed=42`.)
2. Go to **GET /api/dispatch/plan**, `strategy` = `greedy`, **Execute**. Point at the request's time in
   the DevTools Network tab (and, if running locally, the `Dispatch plan: ... elapsedMs=` log line in
   the terminal).

**Say:** "For scale, this demo endpoint generates a reproducible dataset around 30 Indian cities and
replaces everything stored. 5,000 orders and 100 vehicles were generated and stored through the same
chunked upsert in the number of milliseconds you see in elapsedMs. Now the plan for all 5,000 orders:
the browser shows the full round trip, including serializing the response. Note the many unassigned
orders: 100 vehicles carry about 30,000 kg against about 127,500 kg of demand, so most orders cannot
fit, and every HIGH order that fits was placed before any MEDIUM or LOW one. The greedy assignment is
O(n times m) and allocation-free; the benchmarks in docs/PERFORMANCE.md go up to 50,000 orders and 500
vehicles."

(Read the actual numbers off the screen; do not quote numbers in advance.)

## 4:25 - 4:50 Tests and coverage

**Screen:** Terminal with the finished `./mvnw verify` output, then `target/site/jacoco/index.html`.

**Do:** Scroll to the surefire `Tests run:` summary and `BUILD SUCCESS`; then show the JaCoCo total line
coverage.

**Say:** "The project was built test-first with JUnit 5, Mockito, MockMvc and AssertJ. mvnw verify runs
1,122 tests and enforces an 85% line-coverage gate with JaCoCo; we're at 97.9% line coverage. The tests cover every
validation case and exact error message, the HIGH-priority invariant on random data, the exact sample
routes, every OR-Tools fallback, and they prove JDBC batching by counting the actual SQL statements.
Performance tests run separately with mvnw verify -Pperformance and write docs/PERFORMANCE.md."

## 4:50 - 5:00 Close

**Screen:** README "Deploy to Render" section or the Render dashboard.

**Say:** "Everything is in the README: how to run it locally or with Docker, curl examples for every
endpoint, the Postman collection, and the Render deployment with Docker and a health check. Thanks for
watching."
