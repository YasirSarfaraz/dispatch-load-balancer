# Performance Report

Generated: 2026-11-12T22:21:53+05:30

> This file is written automatically by the `@Tag("performance")` tests in `src/test/java/com/freightfox/dispatch/performance`. Do not edit it by hand; re-run the benchmarks instead.

**How to reproduce:** `./mvnw verify -Pperformance` (Windows: `.\mvnw.cmd verify -Pperformance`).

## Machine

| Item | Value |
|---|---|
| OS | Windows 11 10.0 (amd64) |
| CPU | AMD64 Family 25 Model 80 Stepping 0, AuthenticAMD |
| Available processors | 16 |
| Physical memory | 15.4 GB |
| Max heap (Runtime.maxMemory) | 2048 MB |
| Heap JVM flags | -Xms512m -Xmx2g |
| Garbage collectors | G1 Young Generation, G1 Concurrent GC, G1 Old Generation |
| Java | 21.0.6 (Eclipse Adoptium, OpenJDK 64-Bit Server VM 21.0.6+7-LTS) |
| Spring Boot | 3.5.16 |
| Hibernate ORM | 6.6.53.Final |
| OR-Tools (ortools-java) | 9.15.6755 |
| Database | H2 2.3.232 (2024-08-11) (jdbc:h2:mem:dispatchdb) |

## Test settings

### CVRPLIB benchmark

| Item | Value |
|---|---|
| Instances | X-n101-k25, X-n106-k14, X-n110-k13 (CVRPLIB set X, files in src/test/resources/cvrplib) |
| Distance | EUC_2D: Euclidean, rounded to the nearest integer per arc |
| Evaluation | closed tours depot -> stops -> depot |
| Fleet | max(k, routes in the best-known solution), all at the depot, capacity Q |
| Priorities | all customers MEDIUM |
| Greedy 2-opt | 1000 passes / 25 ms per vehicle |
| OR-Tools time limit | 3000 ms (greedy warm start + GUIDED_LOCAL_SEARCH; PATH_CHEAPEST_ARC only without a seed) |
| Thresholds | greedy plan < 2000 ms; OR-Tools plan < time limit + 10000 ms |
| OR-Tools native load (before any timed plan) | 721 ms |

### Dispatch scenarios

| Item | Value |
|---|---|
| Seed | 42 |
| Data | TestDataGenerator: points within 15 km of 30 Indian city centres; weight 1-50; capacity 100-500; priority 30% HIGH / 40% MEDIUM / rest LOW |
| Greedy 2-opt max passes per vehicle | 1000 |
| Greedy 2-opt time budget per vehicle | 25 ms |
| OR-Tools time limit | 3000 ms (greedy warm start + GUIDED_LOCAL_SEARCH; PATH_CHEAPEST_ARC only without a seed) |
| OR-Tools max orders | 1000 (falls back to greedy only above this) |
| Upsert chunk size | 1000 |
| Hibernate JDBC batch size | 50 |
| Warm-up | 6 greedy plans of 10000 x 200 (in memory) and one upsert of 2000 x 50 before the timed scenarios |
| OR-Tools time-limit sensitivity | 1000 x 50 at [10 s, 30 s] besides the configured limit |
| Thresholds: greedy plan | 1,000 x 50 < 2000 ms; 10,000 x 200 < 5000 ms; 50,000 x 500 < 15000 ms |
| Thresholds: upsert (vehicles + orders) | 1,000 < 15000 ms; 10,000 < 30000 ms; 50,000 < 60000 ms; OR-Tools datasets < 15000 ms |
| Thresholds: OR-Tools plan | time limit + 10000 ms |
| Thresholds: end-to-end generatePlan | plan threshold + 15000 ms |

## Results

| Strategy | Orders x Vehicles | Upsert ms | Plan ms | Total distance km | Assigned | Unassigned | Peak heap MB |
|---|---|---:|---:|---:|---:|---:|---:|
| greedy | 1,000 x 50 | 45 | 4 | 74,937.84 | 515 | 485 | 50 |
| greedy | 10,000 x 200 | 274 | 12 | 151,596.81 | 1,661 | 8,339 | 146 |
| greedy | 50,000 x 500 | 863 | 75 | 344,619.59 | 3,576 | 46,424 | 344 |
| ortools | 200 x 20 | 8 | 3,012 | 6,811.19 | 200 | 0 | 50 |
| ortools | 1,000 x 50 | 13 | 3,089 | 68,056.23 | 515 | 485 | 65 |

- **Upsert ms**: `VehicleService.upsertVehicles` + `OrderService.upsertOrders` (the real services, H2 in-memory, chunked findAllById + saveAll with JDBC batching), on empty tables, so every row is an INSERT.
- **Plan ms**: the strategy bean's `plan(orders, vehicles)` alone, on the entities loaded from the database with `findAllOrders()` / `findAllVehicles()`. End-to-end `DispatchService.generatePlan` times are in the details table.
- **Total distance km**: sum of the open-route distances (vehicle location -> stops, no return), Haversine.
- **Peak heap MB**: highest sampled heap use during the scenario (generate + upsert + load + plan + end-to-end), including the Spring context baseline. See methodology below.

### Details

| Strategy | Orders x Vehicles | Upsert vehicles ms | Upsert orders ms | End-to-end generatePlan ms | Order weight / fleet capacity | Assigned H / M / L | Unassigned H / M / L | Heap baseline MB | Heap pool-peak sum MB |
|---|---|---:|---:|---:|---|---|---|---:|---:|
| greedy | 1,000 x 50 | 9 | 36 | 23 | 26,250 / 15,124 | 285 / 230 / 0 | 0 / 152 / 333 | 37 | 50 |
| greedy | 10,000 x 200 | 15 | 259 | 66 | 254,361 / 58,333 | 1,661 / 0 / 0 | 1,246 / 4,058 / 3,035 | 37 | 146 |
| greedy | 50,000 x 500 | 16 | 847 | 134 | 1,275,971 / 149,823 | 3,576 / 0 / 0 | 11,310 / 20,214 / 14,900 | 39 | 382 |
| ortools | 200 x 20 | 5 | 3 | 3,014 | 5,377 / 6,602 | 52 / 75 / 73 | 0 / 0 / 0 | 46 | 50 |
| ortools | 1,000 x 50 | 4 | 9 | 3,083 | 26,250 / 15,124 | 285 / 230 / 0 | 0 / 152 / 333 | 36 | 65 |

The generator draws weights 1-50 (mean 25.5) and capacities 100-500 (mean 300), so whenever the orders outnumber the vehicles by more than about 12 to 1 the total order weight exceeds the fleet capacity (see the weight / capacity column) and many unassigned orders are expected. They are LOW and MEDIUM first because HIGH orders are placed before any other priority.

### JIT warm-up (greedy)

Before the timed greedy scenarios, the greedy strategy planned an in-memory dataset of 10,000 x 200 6 times (same seed):

| Run | Plan ms |
|---|---:|
| Cold (first plan in this JVM) | 26 |
| Warm (fastest of the following runs) | 13 |

A first upsert of 2,000 x 50 (vehicles + orders) took 292 ms (Hibernate/JDBC code paths cold); the tables were then cleared, so the scenario upsert times are warm.

The OR-Tools native libraries had already been loaded in this JVM by an earlier test class (the load time is listed in that class's test settings above), so that one-off cost is not part of any OR-Tools plan time below.

## Greedy vs OR-Tools (same datasets)

| Orders x Vehicles | OR-Tools km | Greedy km | OR-Tools vs greedy distance | OR-Tools assigned (H / M / L) | Greedy assigned (H / M / L) | OR-Tools km per assigned order | Greedy km per assigned order | OR-Tools plan ms | Greedy plan ms |
|---|---:|---:|---:|---|---|---:|---:|---:|---:|
| 200 x 20 | 6,811.19 | 17,219.78 | -60.4% | 200 (52 / 75 / 73) | 200 (52 / 75 / 73) | 34.056 | 86.099 | 3,012 | 0 |
| 1,000 x 50 | 68,056.23 | 74,937.84 | -9.2% | 515 (285 / 230 / 0) | 515 (285 / 230 / 0) | 132.148 | 145.510 | 3,089 | 0 |

Both strategies ran on the same entities loaded from the database. When the assigned counts differ, total distance alone is not a like-for-like comparison (serving more orders needs more driving); km per assigned order is shown as a rough normaliser. OR-Tools' drop penalties are lexicographic by priority and count orders, not weight, so it maximises the number of served orders per priority level before it minimises distance. Greedy places heavier orders first within a priority.

### Priority check (OR-Tools)

No OR-Tools result left a HIGH order unassigned while serving a lower priority.

### OR-Tools time-limit sensitivity (1,000 x 50, same data)

| Time limit | Strategy | Assigned | Assigned H / M / L | Unassigned H / M / L | Total distance km | Plan ms |
|---:|---|---:|---|---|---:|---:|
| 3 s | ortools | 515 | 285 / 230 / 0 | 0 / 152 / 333 | 68,056.23 | 3,089 |
| 10 s | ortools | 515 | 285 / 230 / 0 | 0 / 152 / 333 | 51,281.26 | 10,084 |
| 30 s | ortools | 515 | 285 / 230 / 0 | 0 / 152 / 333 | 37,445.18 | 30,082 |

The configured limit is the first row (the scenario above); the others use a strategy instance built with the production constructor and only the time limit changed. Because the objective is lexicographic (served HIGH, then MEDIUM, then LOW, then distance) and the search starts from the greedy plan, a longer limit can only keep or improve the priority mix; with the mix unchanged, the extra time goes into shorter routes.

## CVRPLIB benchmark (X instances)

| Instance | Customers | Q | k | BKS routes | Fleet given | Best-known cost | Strategy | Served | Routes used | CVRPLIB cost (closed tours) | Gap vs best-known | Open-route cost (no return legs) | Plan ms |
|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|
| X-n101-k25 | 100 | 206 | 25 | 26 | 26 | 27,591 | greedy | 100 / 100 | 26 | 37,668 | +36.5% | 22,834 | 1 |
| X-n101-k25 | 100 | 206 | 25 | 26 | 26 | 27,591 | ortools | 100 / 100 | 26 | 32,152 | +16.5% | 17,478 | 3,145 |
| X-n106-k14 | 105 | 600 | 14 | 14 | 14 | 26,362 | greedy | 105 / 105 | 14 | 37,303 | +41.5% | 22,152 | 0 |
| X-n106-k14 | 105 | 600 | 14 | 14 | 14 | 26,362 | ortools | 105 / 105 | 14 | 29,673 | +12.6% | 15,334 | 3,008 |
| X-n110-k13 | 109 | 66 | 13 | 13 | 13 | 14,971 | greedy | 109 / 109 | 13 | 21,853 | +46.0% | 16,212 | 0 |
| X-n110-k13 | 109 | 66 | 13 | 13 | 13 | 14,971 | ortools | 109 / 109 | 13 | 16,618 | +11.0% | 10,472 | 3,008 |

Instances and best-known solutions (BKS) are the genuine CVRPLIB files (Uchoa et al. 2017, set X),
downloaded from https://galgos.inf.puc-rio.br/cvrplib/ and stored in `src/test/resources/cvrplib`.
The test first re-evaluates each `.sol` file with its own evaluator and asserts that it reproduces the
published cost exactly, which validates the parser, the node numbering and the metric.

How the instances are fed to the production strategies (test-only adapter, no main-code change):
- Every vehicle starts at the depot; capacity = Q from the file; every customer becomes an order with
  weight = demand and the same priority (MEDIUM), so priorities play no role.
- Fleet given = max(k, routes in the BKS). k in the name is only a lower bound on the number of
  routes; X-n101-k25's best-known solution uses 26 routes, so 25 vehicles could not reproduce it.
- Distances: a test-only `DistanceCalculator` returns the CVRPLIB EUC_2D metric, the Euclidean
  distance rounded to the nearest integer per arc. The x/y coordinates are passed as lat/lon scaled by
  1e-4 degrees per unit (at most 0.1 degrees, next to the equator), because the greedy strategy ranks
  candidates with its internal Haversine table; at that scale Haversine is proportional to Euclidean
  distance to within a few parts per million, so greedy's choices follow Euclidean geometry. OR-Tools and every
  reported cost use the calculator, i.e. the exact rounded CVRPLIB distances.
- Evaluation (the "CVRPLIB cost" column) uses the CVRPLIB convention: closed tours depot -> stops ->
  depot, rounded Euclidean per arc.

Model differences, so read the gap with care:
- Our production model uses **open routes** (no return to the depot), so both strategies minimise the
  open-route cost and ignore the return legs that the benchmark charges. The open-route cost column
  shows what was actually optimised.
- Production uses Haversine on lat/lon, priorities, and may **drop** orders that do not fit; the
  benchmark must visit every customer. If a strategy dropped customers, its gap is not comparable
  (fewer customers served makes a tour cheaper) and the table says so.
- OR-Tools ran with the production settings (warm start from the greedy plan + GUIDED_LOCAL_SEARCH,
  3 s limit). The greedy seed protects the priority rule in production but is a worse starting
  shape than PATH_CHEAPEST_ARC on these pure-distance instances (cold-start gaps were 8.4%, 6.3%
  and 14.0% in an earlier run);
  state-of-the-art BKS come from hours of specialised search, so a gap of several percent is expected.

## Methodology and caveats

- One `@SpringBootTest` (the real application context, H2 in-memory database in PostgreSQL mode) runs
  the scenarios in a fixed order. Data comes from the production `TestDataGenerator` with a fixed seed.
  Before each scenario both tables are cleared through the services (`deleteAllOrders`,
  `deleteAllVehicles`), so every upsert inserts into empty tables.
- Each measurement is a **single run** (wall clock, `System.nanoTime`). Greedy timings are taken after
  the JIT warm-up shown above; the cold figure shows what the first request after start-up costs.
  Numbers vary between machines and between runs (GC, CPU boost, background load); compare orders of
  magnitude, not individual milliseconds.
- OR-Tools' Guided Local Search always uses its full time limit, so its plan time is roughly the time
  limit plus model building; its result can differ slightly between runs.
- Every result is checked by assertions: the strategy that ran (OR-Tools at exactly 1,000 orders
  must not fall back, because the cap rejects only orders.size() > max-orders), one route per
  vehicle in input order, no vehicle over capacity, reported load and distance consistent with the
  stops, every order exactly once (assigned or unassigned), valid unassigned reasons and ordering, and
  for greedy the HIGH-priority invariant (an unassigned HIGH order is heavier than every vehicle's
  remaining capacity after the HIGH orders were placed). The end-to-end response is checked too.
- Thresholds (generous, to avoid flaky builds): see the test settings above. A failed threshold or
  invariant fails the build; the row is still recorded in this file first.
- Heap: a background thread samples `MemoryMXBean.getHeapMemoryUsage().getUsed()` every 5 ms after a
  `System.gc()` at the start of each scenario; "Peak heap MB" is the maximum sample (it includes the
  live Spring context baseline and garbage not yet collected, so it depends on the GC and on -Xmx).
  The details table also shows the baseline after GC and the sum of the per-pool peaks from
  `MemoryPoolMXBean.getPeakUsage()` (reset per scenario), which is an upper bound because the pools
  peak at different moments. OR-Tools allocates its solver memory natively (off-heap), so that part
  is not included in the heap figures.
- H2 in-memory is much faster than a networked PostgreSQL for the upsert; with the `prod` profile, add
  `reWriteBatchedInserts=true` to the JDBC URL to keep batched inserts efficient.
