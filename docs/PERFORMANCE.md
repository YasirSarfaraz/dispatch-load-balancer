# Performance on the live deployment

[Back to README](../README.md)

Every figure below was measured against the **live deployment** at
https://dispatch-load-balancer-yl5j.onrender.com, which runs the `prod` profile on **PostgreSQL 16**
(Render managed database, same region as the service). The figures come from the Postman collection's
folder **6. Performance (large datasets)**, which goes through the public API. The collection was run
twice in a row on 2026-11-13, and all 74 requests and 398 assertions passed in both runs. You can
reproduce the figures by running the collection.

- **Server time** is the application's own processing time, from the request reaching the service until
  the response is written, database work included. The service reports it in the `Server-Timing`
  response header on every API response.
- **End-to-end** is what the client measured: server time plus upload and download over the internet,
  from India to Render's Singapore region.
- **First run** was the first run after a deploy, while the JVM was still compiling hot code.
  **Repeat run** was the next one, on a warm JVM.
- **Hardware:** Render's free plan for both the web service (a 0.1 share of one CPU, 512 MB RAM) and the
  database. These are the smallest instances Render offers, so the figures are a floor, not a ceiling.

## Results

| Dataset (orders x vehicles) | Operation | Server time, first run | Server time, repeat run | End-to-end, repeat run | Response |
|---|---|---:|---:|---:|---:|
| 1,000 x 50 | Upload 1,000 orders (insert) | 3.9 s | 0.6 s | 0.9 s | - |
| 1,000 x 50 | Plan with greedy | 1.0 s | 0.2 s | 0.4 s | 0.16 MB |
| 1,000 x 50 | Plan with OR-Tools (3 s search) | 4.5 s | 4.4 s | 4.7 s | 0.16 MB |
| 10,000 x 200 | Upload 10,000 orders (insert) | 12.9 s | 5.8 s | 7.3 s | - |
| 10,000 x 200 | Upload the same 10,000 orders again (existing IDs) | 4.4 s | 2.7 s | 4.3 s | - |
| 10,000 x 200 | Plan with greedy | 2.3 s | 0.7 s | 2.3 s | 1.8 MB |
| 20,000 x 400 | Upload 20,000 orders in one request (2.7 MB body) | 16.0 s | 8.1 s | 16.3 s | - |
| 20,000 x 400 | Plan with greedy | 3.0 s | 1.2 s | 2.6 s | 3.6 MB |
| 5,000 x 100 | Generate and store test data (`POST /test-data`) | 2.6 s | 1.5 s | 1.7 s | - |
| 30 x 5 (sample) | Plan with greedy | 0.04 s | 0.13 s | 0.3 s | 5 KB |

The same runs also checked every one of these plans for correctness:
- no vehicle over capacity;
- every order accounted for exactly once;
- distances matching an independent Haversine calculation to within 0.01 km;
- no unassigned order that would still fit a vehicle.

**Durability.** After the runs, the service was restarted from the Render dashboard. The stored sample
dataset was still there afterwards and gave the same plan (22 orders assigned, 73.42 km).

## What the numbers show

- **Large inputs are handled in one request.** 20,000 orders, a 2.7 MB body, are validated and written
  to PostgreSQL in a single call on the smallest instances available.
- **The upsert is efficient.** Orders are written in chunks of 1,000: one `SELECT` per chunk to find
  existing rows, then batched `INSERT`s and `UPDATE`s, sent as multi-row statements by the PostgreSQL
  driver (`reWriteBatchedInserts`). Re-posting the same 10,000 orders takes about half the time of inserting
  them: the IDs already exist, and Hibernate's dirty checking skips rows whose values did not change.
- **Greedy planning scales linearly.** Twenty times more orders (1,000 to 20,000) and eight times more
  vehicles take about six times longer to plan on the warm instance.
- **OR-Tools is bounded by its time limit.** It searches for 3 s and then returns, plus model building.
  Above 1,000 orders the service uses greedy automatically, and the response's `strategy` field says
  which algorithm ran.
- **A warm JVM is up to about six times faster.** First requests after a deploy include JIT compilation. The first
  OR-Tools request also loads the native solver library: the sample-dataset OR-Tools plan took 10.7 s on
  the first run and 3.2 s on the repeat run.

## Context

- **Why the collection stops at 20,000 orders.** The service accepts much larger inputs: a 50,000-order
  run passed on the live instance before the switch to PostgreSQL. But on a tenth of a CPU, cleaning up
  memory after such a dataset can pause the service for several seconds. Once, that pause plus an
  OR-Tools solve made the instance miss Render's 5-second health check and restart. The collection must
  be safe to run repeatedly on a shared demo server, so it stays at 20,000.
- **With a full CPU** the engine stores 50,000 orders in about 1 s and plans them in under 0.1 s. Those
  figures come from the engine benchmark, which `./mvnw verify -Pperformance` runs locally and writes to
  `target/performance-report.md`.
- **Free-tier sleep.** After about 15 idle minutes the free web service sleeps, and the first request
  then takes up to about 2 minutes. The collection's first folder wakes it. Stored data is unaffected.
