# Testing

[Back to README](../README.md)

```bash
./mvnw test                    # unit + integration tests (Windows: .\mvnw.cmd test)
./mvnw verify                  # tests + JaCoCo report + 85% line-coverage gate (the build fails below 85%)
./mvnw verify -Pperformance    # performance and CVRPLIB benchmarks only; writes docs/PERFORMANCE.md
```

| Metric | Result |
|---|---|
| Tests (`./mvnw verify`) | **1,122**, 0 failures (plus 10 performance/CVRPLIB tests in `-Pperformance`) |
| Line coverage | **97.87%** (1,196 of 1,222 lines) |
| Branch coverage | **87.76%** (380 of 433 branches) |

Coverage report: `target/site/jacoco/index.html`. The normal build excludes `@Tag("performance")`
tests. Mockito is attached as a `-javaagent`, as recommended for JDK 21. The
[Postman collection](../postman/DispatchLoadBalancer.postman_collection.json) adds 119 HTTP-level
assertions (happy path, upsert, validation errors, edge cases, test data, operations), run with the
Collection Runner.

## What the suite proves

29 test classes under `src/test/java`, plus shared test helpers.

| Area | Key test classes | What they prove |
|---|---|---|
| Distance | `HaversineDistanceCalculatorTest`, `HaversineTableTest` | Same point = 0, Connaught Place to Karol Bagh about 14.4 km, symmetry; the trig table matches the calculator |
| Greedy | `GreedyPriorityDispatchStrategyTest`, `GreedySampleDatasetTest`, `GreedyRandomizedInvariantTest`, `RouteOptimizerTest` | Capacity never exceeded, priority order, nearest vehicle, tie-breaks, unassigned reasons, empty orders, single vehicle, determinism, exact sample routes (73.42 km), the HIGH invariant on randomized data, 2-opt never lengthens a route |
| OR-Tools | `OrToolsDispatchStrategyTest`, `OrToolsWarmStartTest`, `OrToolsCvrpSolverTest`, `SeedRoutesTest`, `UnassignedCountsTest`, `DropPenaltiesTest`, `CvrpModelBuilderTest`, `OrToolsNativeLoaderTest` | Sample invariants, HIGH kept over MEDIUM/LOW, open routes, the greedy warm start (including a regression on 1,000 x 50 capacity-scarce data), the priority guard, the 2-solve bulkhead, every fallback (cap, native load failure, no solution, solver exception, invalid solutions), penalty arithmetic and overflow handling |
| Registry | `DispatchStrategyRegistryTest` | Case-insensitive lookup, default, unknown names, duplicate names |
| Services | `DefaultOrderServiceTest`, `DefaultVehicleServiceTest`, `DefaultDispatchServiceTest` | Upsert behaviour, ordering of checks, no-vehicles error |
| Persistence | `UpsertJdbcBatchingIntegrationTest`, `ChunkedUpserterConflictIntegrationTest` | JDBC batching proven by counting real SQL statements (120 new orders = 1 SELECT + 3 batches of 50/50/20, no single-row inserts; 2,500 orders with 500 existing = 3 SELECTs, 40 INSERT batches, 10 UPDATE batches; unchanged rows = no SQL); a concurrent duplicate insert surfaces as 409, not 500 |
| Web layer | `DispatchControllerTest`, `StrictJsonParsingTest`, `GlobalExceptionHandlerTest`, `JsonErrorTranslatorTest`, `TestDataControllerTest`, `TestDataControllerDisabledTest` | Every validation case, exact success bodies and error messages, strict parsing (overflow, `"10"` as a number, trailing tokens), 404/405/406/415 handling, error capping |
| Mappers, model | `DispatchPlanMapperTest`, `RequestMapperTest`, `PriorityTest` | Distance formatting (`"14.44 km"`), visiting order preserved, DTO mapping |
| End to end | `SampleDatasetIntegrationTest`, `DispatchApiIntegrationTest`, `TestDataIntegrationTest`, `LargeBatchIntegrationTest` | Sample dataset through HTTP + H2 for both strategies (all 12 HIGH assigned, capacity, 30 orders accounted for, unassigned all LOW, OR-Tools <= greedy); full wiring; test-data generation and the OR-Tools cap; a 50,000-order request body |
| Test data | `TestDataGeneratorTest`, `TestDataServiceTest` | Determinism per seed, ranges, priority mix, replace semantics and limits |

## Benchmarks

`./mvnw verify -Pperformance` runs only the `@Tag("performance")` tests (with `-Xms512m -Xmx2g`, JaCoCo
skipped), asserts every invariant on large generated datasets and on three CVRPLIB instances, and writes
the results with machine information to [PERFORMANCE.md](PERFORMANCE.md).
