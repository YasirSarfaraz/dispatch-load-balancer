# Performance on the live deployment

[Back to README](../README.md)

Every figure below was measured against the **live deployment** at
https://dispatch-load-balancer-yl5j.onrender.com, through the public API, by the Postman collection's
folder **6. Performance (large datasets)**. The collection was run twice in a row on 2026-11-13, and all
74 requests and 398 assertions passed in both runs. You can reproduce the figures by running the
collection.

- **Server time** is the application's own processing time, from the request reaching the service until
  the response is written. The service reports it in the `Server-Timing` response header on every API
  response.
- **End-to-end** is what the client measured: server time plus upload and download over the internet,
  from India to Render's Singapore region.
- **First run** was the first run after a restart, while the JVM was still compiling hot code.
  **Repeat run** was the next one, on a warm JVM.
- **Hardware:** Render's free plan, a 0.1 share of one CPU and 512 MB RAM, with H2 in memory. This is
  the smallest instance Render offers, so the figures are a floor, not a ceiling.

## Results

| Dataset (orders x vehicles) | Operation | Server time, first run | Server time, repeat run | End-to-end, repeat run | Response |
|---|---|---:|---:|---:|---:|
| 1,000 x 50 | Upload 1,000 orders (insert) | 5.1 s | 1.4 s | 1.7 s | - |
| 1,000 x 50 | Plan with greedy | 0.9 s | 0.2 s | 0.6 s | 0.16 MB |
| 1,000 x 50 | Plan with OR-Tools (3 s search) | 4.5 s | 4.6 s | 5.0 s | 0.16 MB |
| 10,000 x 200 | Upload 10,000 orders (insert) | 12.3 s | 4.3 s | 5.0 s | - |
| 10,000 x 200 | Upload the same 10,000 orders again (update) | 6.4 s | 3.9 s | 4.6 s | - |
| 10,000 x 200 | Plan with greedy | 1.9 s | 0.7 s | 2.1 s | 1.8 MB |
| 20,000 x 400 | Upload 20,000 orders in one request (2.7 MB body) | 11.5 s | 6.9 s | 8.7 s | - |
| 20,000 x 400 | Plan with greedy | 2.3 s | 1.6 s | 3.0 s | 3.6 MB |
| 5,000 x 100 | Generate and store test data (`POST /test-data`) | 2.0 s | 0.9 s | 1.1 s | - |
| 30 x 5 (sample) | Plan with greedy | 0.24 s | 0.006 s | 0.2 s | 5 KB |

The same runs also checked every one of these plans for correctness:
- no vehicle over capacity;
- every order accounted for exactly once;
- distances matching an independent Haversine calculation to within 0.01 km;
- no unassigned order that would still fit a vehicle.

### 50,000 orders x 500 vehicles

A separate run on the same instance uploaded **50,000 orders in one 6.8 MB request** (28.6 s server time)
and planned them with greedy (7.0 s server time, 9.3 MB response). Every check above passed.

The collection stops at 20,000 orders for a reason. On a tenth of a CPU, the memory cleanup after a
dataset this large can pause the service for several seconds. In one test, that pause, combined with an
OR-Tools solve straight afterwards, made the instance miss Render's 5-second health check, and Render
restarted it. The collection must be safe to run repeatedly on a shared demo server, so it stays at
20,000. Any instance with a full CPU handles 50,000 easily; see [Context](#context).

## What the numbers show

- **Large inputs are handled in one request.** 20,000 orders, a 2.7 MB body, are validated and stored in
  a single call on the smallest cloud instance available; 50,000 were handled in a one-off run.
- **The upsert is efficient.** Orders are written in chunks of 1,000: one `SELECT` per chunk to find
  existing rows, then batched `INSERT`s and `UPDATE`s. Re-posting 10,000 existing orders, all updates,
  is no slower than inserting them.
- **Greedy planning scales linearly.** Twenty times more orders (1,000 to 20,000) and eight times more
  vehicles take about eight times longer to plan on the warm instance.
- **OR-Tools is bounded by its time limit.** It searches for 3 s and then returns, plus model building.
  Above 1,000 orders the service uses greedy automatically, and the response's `strategy` field says
  which algorithm ran.
- **A warm JVM is 2–4 times faster.** First requests after a restart include JIT compilation. The first
  OR-Tools request also loads the native solver library: the sample-dataset OR-Tools plan took 9.3 s on
  the first run and 3.0 s on the repeat run.

## Context

- The free instance shares a small slice of one CPU, so database-heavy steps are slower than on a
  developer laptop. With a full CPU, 50,000 orders are stored in about 1 s and planned in under 0.1 s.
  Those figures come from the engine benchmark, which `./mvnw verify -Pperformance` runs locally and
  writes to `target/performance-report.md`.
- After about 15 idle minutes the free instance sleeps, and the first request then takes up to about
  2 minutes while it starts. The collection's first folder wakes it.
- Data is held in memory, so a restart clears it. The collection's last folder reloads the sample
  dataset.
