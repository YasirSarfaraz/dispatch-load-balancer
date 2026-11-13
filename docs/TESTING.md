# Testing

[Back to README](../README.md)

The project is tested on three levels:
- **JUnit:** a suite that runs on every build.
- **PostgreSQL:** integration tests that run the app with the real production profile against
  PostgreSQL 16.
- **Postman:** an evaluation suite that exercises the live deployment, which runs on PostgreSQL, over
  HTTP.

| Suite | Scope | Result |
|---|---|---|
| JUnit (`./mvnw verify`) | Unit, integration and end-to-end tests | **1,140** tests, 0 failures, 0 skipped |
| PostgreSQL integration (`PostgresIntegrationTest`) | The app with the `prod` profile against PostgreSQL 16 (Testcontainers) | 6 of the 1,140; they need Docker and are skipped automatically without it |
| JaCoCo | Coverage of the JUnit suite (build fails below 85% lines) | **97.93%** lines (1,232 of 1,258), **87.93%** branches (386 of 439) |
| Postman | 74 requests against the live server | **398** assertions, all passing |

- [JUnit suite](#junit-suite)
- [Postman evaluation suite](#postman-evaluation-suite)
- [Engine benchmark](#engine-benchmark)

## JUnit suite

```bash
./mvnw test       # unit + integration tests (Windows: .\mvnw.cmd test)
./mvnw verify     # tests + JaCoCo report (target/site/jacoco/index.html) + 85% line-coverage gate
```

The suite was built test-first with JUnit 5, Mockito, MockMvc and AssertJ. It has 40 test classes under
`src/test/java`, plus shared test helpers.

| Area | Key test classes | What they prove |
|---|---|---|
| Distance | `HaversineDistanceCalculatorTest`, `HaversineTableTest` | Same point = 0, Connaught Place to Karol Bagh about 14.4 km, symmetry; the trig table matches the calculator |
| Greedy | `GreedyPriorityDispatchStrategyTest`, `GreedySampleDatasetTest`, `GreedyRandomizedInvariantTest`, `RouteOptimizerTest` | Capacity never exceeded, priority order, nearest vehicle, tie-breaks, unassigned reasons, empty orders, single vehicle, determinism, exact sample routes (73.42 km), the HIGH invariant on randomized data, 2-opt never lengthens a route |
| OR-Tools | `OrToolsDispatchStrategyTest`, `OrToolsWarmStartTest`, `OrToolsCvrpSolverTest`, `SeedRoutesTest`, `UnassignedCountsTest`, `DropPenaltiesTest`, `CvrpModelBuilderTest`, `OrToolsNativeLoaderTest` | HIGH kept over MEDIUM/LOW, open routes, the greedy warm start, the priority guard, the 2-solve bulkhead, every fallback (cap, native load failure, no solution, solver exception, invalid solution), penalty arithmetic and overflow handling |
| Registry | `DispatchStrategyRegistryTest` | Case-insensitive lookup, default, unknown and duplicate names |
| Services | `DefaultOrderServiceTest`, `DefaultVehicleServiceTest`, `DefaultDispatchServiceTest`, `DispatchSnapshotConsistencyIntegrationTest` | Upsert behaviour, ordering of checks, no-vehicles error; vehicles and orders read in one read-only REPEATABLE READ transaction, planning outside any transaction |
| Persistence | `UpsertJdbcBatchingIntegrationTest`, `ChunkedUpserterConflictIntegrationTest`, `PostgresIntegrationTest` | JDBC batching proven by counting real SQL statements (120 new orders = 1 SELECT + 3 batches of 50/50/20; 2,500 orders with 500 existing = 3 SELECTs, 40 INSERT batches, 10 UPDATE batches; unchanged rows = no SQL); a concurrent duplicate insert returns 409, not 500; on PostgreSQL 16: Flyway migrations, the sample plan (73.42 km), OR-Tools, a 5,000-order insert-then-upsert without duplicates, the database CHECK constraints, and a commit made between reading vehicles and orders staying out of the plan (fails without the snapshot fix) |
| Web layer | `DispatchControllerTest`, `StrictJsonParsingTest`, `GlobalExceptionHandlerTest`, `JsonErrorTranslatorTest`, `TestDataControllerTest`, `TestDataControllerDisabledTest`, `ServerTimingIntegrationTest` | Every validation case, exact success bodies and error messages, strict parsing, 404/405/406/415 handling, error capping, the `Server-Timing` header and the root redirect |
| Mappers, model | `DispatchPlanMapperTest`, `RequestMapperTest`, `PriorityTest` | Distance formatting (`"14.44 km"`), visiting order preserved, DTO mapping |
| End to end | `SampleDatasetIntegrationTest`, `DispatchApiIntegrationTest`, `TestDataIntegrationTest`, `LargeBatchIntegrationTest` | Sample dataset through HTTP + H2 for both strategies; full wiring; test-data generation and the OR-Tools cap; a 50,000-order request body |
| Test data | `TestDataGeneratorTest`, `TestDataServiceTest` | Determinism per seed, ranges, priority mix, replace semantics and limits |
| Configuration | `ProdProfileConfigurationTest`, `DatabaseInfoContributorTest` | The production profile binds exactly as at start-up with the variables Render injects (datasource URL, pool settings, `ddl-auto: validate`); `/actuator/info` reports the database |

## Postman evaluation suite

[`postman/DispatchLoadBalancer.postman_collection.json`](../postman/DispatchLoadBalancer.postman_collection.json)
targets the live deployment by default. Import it and click **Run**. The folders and what each checks
are listed in the [README](../README.md#how-to-evaluate).

Besides the per-request tests, a collection-level script runs after every request:
- **It remembers what was uploaded:** the orders and vehicles from each POST, and a reset on each DELETE.
- **It verifies every dispatch plan the server returns**, independently of the server code:

| Check | Rule |
|---|---|
| Capacity | No vehicle's `totalLoad` exceeds its capacity; `totalLoad` equals the sum of its orders' weights |
| Distance | Each `totalDistance` equals a Haversine calculation done in Postman over the open route (vehicle location, then each stop in order), to within 0.01 km |
| Completeness | Every uploaded order appears exactly once, assigned or unassigned; assigned orders come back unchanged and with their address |
| Priority | No unassigned order would still fit a vehicle (greedy); no HIGH order is dropped while it would fit (OR-Tools) |
| Format | Every vehicle listed once and sorted; distances formatted `"<km> km"`; valid unassigned reasons; a `Server-Timing` header |

The collection was run twice in a row against the live server; both runs passed all 74 requests and
398 assertions.
The measurements from its performance folder are in [PERFORMANCE.md](PERFORMANCE.md).

## Engine benchmark

`./mvnw verify -Pperformance` runs the `@Tag("performance")` tests, which the normal build excludes.
They plan generated datasets of up to 50,000 orders x 500 vehicles in-process, and compare both
strategies on three CVRPLIB benchmark instances with known best solutions. The results are written to
`target/performance-report.md`. This benchmark measures the algorithms on a developer machine;
[PERFORMANCE.md](PERFORMANCE.md) measures the deployed service.
