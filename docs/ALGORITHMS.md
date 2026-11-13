# Algorithms

[Back to README](../README.md)

- [Haversine distance](#haversine-distance)
- [Greedy strategy (default)](#greedy-strategy-default)
- [OR-Tools CVRP strategy](#or-tools-cvrp-strategy)
- [Sample dataset walk-through](#sample-dataset-walk-through)

## Haversine distance

```
h = sin^2(dLat / 2) + cos(lat1) * cos(lat2) * sin^2(dLon / 2)
d = 2 * R * asin(sqrt(h)),   R = 6371.0 km
```

`h` is clamped to [0, 1]. Because `d` is strictly increasing in `h`, hot loops compare `h` directly.
`HaversineTable` precomputes the half-angle sines and cosines of every point, so the greedy inner loop
evaluates distances without any trigonometric call or allocation. Connaught Place to Karol Bagh
(Delhi) is about 14.44 km.

## Greedy strategy (default)

`GreedyPriorityDispatchStrategy`, selected with `strategy=greedy` or no parameter.

1. **Sort** orders by priority (HIGH, MEDIUM, LOW), then `packageWeight` descending (heavier first:
   large packages are the hardest to fit), then `orderId`. Fully deterministic.
2. **Track** each vehicle's remaining capacity and route end; the route end starts at the vehicle's
   current location.
3. **Assign** each order, in that sequence, to the vehicle with enough remaining capacity whose route
   end is nearest (cheapest insertion at the route end). Ties go to the smaller `vehicleId`. The
   vehicle's route end then moves to the order.
4. **Unassigned**: if no vehicle has room, the order is reported with `Package weight exceeds capacity
   of every vehicle` (heavier than the largest vehicle) or `Insufficient remaining capacity across
   fleet`. An order whose weight equals a vehicle's capacity fits that empty vehicle.
5. **Improve each route**: nearest-neighbour ordering from the vehicle start (kept only if shorter),
   then first-improvement 2-opt on the open path (a move is kept only if it shortens the route).
   2-opt is capped by `app.dispatch.greedy.two-opt-max-iterations` (1000 passes) and
   `app.dispatch.greedy.two-opt-time-budget` (25 ms per vehicle). A final guard keeps the assignment
   order if the optimized sequence is not shorter.
6. **Distance** is the open route: vehicle location -> stop 1 -> ... -> last stop (no return trip).

**HIGH-priority guarantee.** All HIGH orders are placed before any MEDIUM or LOW order, so MEDIUM and
LOW orders can never take capacity from a HIGH order. An unassigned HIGH order is therefore heavier
than every vehicle's remaining capacity after only the HIGH orders were placed. This invariant is
tested on randomized data (`GreedyRandomizedInvariantTest`).

**Complexity** (n orders, m vehicles, k stops on one route):

| Step | Cost |
|---|---|
| Sort | O(n log n) |
| Assignment | O(n * m), allocation-free, trig-free inner loop; O(n + m) extra memory |
| Nearest-neighbour per route | O(k^2); skipped above 10,000 stops |
| 2-opt per route | O(k^2) per pass, at most 1000 passes and 25 ms per vehicle |
| Distance matrix per route | Precomputed for routes up to 1,024 points (at most 8 MB), evaluated on the fly above that |

## OR-Tools CVRP strategy

`OrToolsDispatchStrategy`, selected with `strategy=ortools`, models the same problem as a Capacitated
Vehicle Routing Problem for the Google OR-Tools routing solver.

- **Nodes**: `0 .. V-1` one start node per vehicle (its current location), `V .. V+N-1` one node per
  order, and `V+N` **one dummy end node shared by all vehicles**. Every arc into and out of the dummy
  end costs 0, which turns the closed tours of a classic CVRP into **open routes** (no return trip),
  matching the greedy distance definition.
- **Arc costs**: a `(V+N+1)^2` matrix of Haversine distances in whole metres
  (`Math.round(km * 1000)`), one evaluator for every vehicle.
- **Capacity dimension**: demand = `packageWeight` per order node (0 for start and end nodes), per
  vehicle capacity, no slack, cumul starts at 0.
- **Optional visits**: every order node is a single-node disjunction with a drop penalty by priority.
- **Search**: the solver is **warm-started from the greedy plan** (greedy runs once per call, in
  milliseconds; its routes are loaded with `readAssignmentFromRoutes` and solved with
  `solveFromAssignmentWithParameters`), then `GUIDED_LOCAL_SEARCH` improves it until the time limit
  `app.dispatch.ortools.time-limit` (default 3 s). `PATH_CHEAPEST_ARC` is used when no seed is available
  (empty greedy plan, or a seed OR-Tools rejects). An OR-Tools plan therefore takes about 3 s (plus
  about 0.9 s to load the native library on the first call).
- **Why the warm start**: with a cold `PATH_CHEAPEST_ARC` start, the performance suite showed that on
  capacity-scarce data (1,000 orders x 50 vehicles, 26,250 weight vs 15,124 capacity) a 3 s search left
  110 HIGH orders unassigned while serving MEDIUM/LOW ones; Guided Local Search was still repairing it
  at 30 s. Starting from greedy (which places HIGH first by construction) and minimizing the
  lexicographic objective below means OR-Tools can never drop more HIGH (then MEDIUM, then LOW) orders
  than greedy. The trade-off: on the CVRPLIB benchmark the greedy seed gives a worse starting shape on
  two of three instances (see [PERFORMANCE.md](PERFORMANCE.md)).
- **Priority guard**: after solving, the unassigned counts per priority are compared with greedy's
  (HIGH, then MEDIUM, then LOW). If OR-Tools is worse, a WARN is logged and the greedy plan is returned
  (`"strategy": "greedy"`).
- **Result**: dropped orders are reported with `Dropped by optimizer: insufficient capacity`. Loads
  and distances in km are recomputed from the real coordinates, and the solution is re-checked
  (capacity, each order at most once, one route per vehicle) before it is returned.
- **Concurrency**: at most 2 solves run at once (a bulkhead); further concurrent `ortools` requests get
  the greedy plan.

### Drop penalties (lexicographic objective)

The solver minimizes `total arc cost + sum of penalties of dropped orders`. With `maxArc` the largest
matrix entry, `routeBound = maxArc * (orders + vehicles)` is an upper bound on the distance of any
solution (every visited node has exactly one outgoing arc). With `nLow` and `nMedium` the numbers of LOW
and MEDIUM orders in the model:

```
LOW    = routeBound + 1
MEDIUM = LOW * nLow + routeBound + 1
HIGH   = MEDIUM * nMedium + LOW * nLow + routeBound + 1
```

Dropping one HIGH order therefore costs more than dropping every MEDIUM and LOW order plus any
possible distance; dropping one MEDIUM costs more than dropping every LOW order plus any distance; and
dropping one LOW costs more than any distance saving. The objective is lexicographic: first minimize
dropped HIGH orders, then MEDIUM, then LOW, and only then distance. Penalties count orders, not weight,
so the solver serves as many LOW orders as fit (even far ones) before it minimizes distance. All penalty
arithmetic uses exact (overflow-checked) operations; if the worst-case objective would not fit in int64,
the internal cost unit is coarsened (1 m, 10 m, 100 m, ...). Reported distances always come from the
real coordinates.

### Cap and fallbacks

OR-Tools never causes a 500. The strategy logs a WARN and returns the greedy plan (response
`"strategy": "greedy"`) when:

- there are more orders than `app.dispatch.ortools.max-orders` (default 1,000; the matrix grows as N
  squared);
- the native library cannot be loaded (loaded lazily, so start-up never fails because of it);
- two solves are already running;
- the solver finds no solution within the time limit;
- the solver throws, or its output fails the safety re-check or the priority guard.

Orders heavier than every vehicle are excluded from the model up front and reported with `Package
weight exceeds capacity of every vehicle`. With no orders, every vehicle gets an empty route without
calling the solver.

## Sample dataset walk-through

`samples/orders.json` and `samples/vehicles.json` (also in `src/test/resources/data/`) are a Delhi NCR
dataset: 30 orders at Connaught Place, Karol Bagh, Sector 18 Noida and Cyber Hub Gurgaon, and 5
vehicles. It is an intentional over-capacity edge case:

| | HIGH | MEDIUM | LOW | Total |
|---|---|---|---|---|
| Orders | 12 | 8 | 10 | 30 |
| Weight | 275 | 145 | 225 | **645** |

Fleet capacity: VEH001 100, VEH002 80, VEH003 120, VEH004 90, VEH005 110 = **500**. At least 145 units
cannot be delivered, so some LOW orders must be unassigned.

**Greedy result** (deterministic; asserted by `GreedySampleDatasetTest`):

| Vehicle | Stops in visiting order | Load / capacity | Distance |
|---|---|---|---|
| VEH001 | ORD026, ORD007, ORD011, ORD015, ORD001 | 100 / 100 | 14.44 km |
| VEH002 | ORD020, ORD023, ORD027 | 80 / 80 | 0.00 km |
| VEH003 | ORD013, ORD005, ORD009, ORD017, ORD010 | 115 / 120 | 24.74 km |
| VEH004 | ORD029, ORD004, ORD008, ORD012 | 90 / 90 | 19.80 km |
| VEH005 | ORD018, ORD021, ORD025, ORD002, ORD006 | 105 / 110 | 14.44 km |

22 orders assigned (all 12 HIGH, all 8 MEDIUM, 2 LOW), **73.42 km** in total. Unassigned, all LOW with
`Insufficient remaining capacity across fleet`: ORD003, ORD014, ORD016, ORD019, ORD022, ORD024, ORD028,
ORD030.

**OR-Tools result** (3 s time limit): 24 orders assigned (all 12 HIGH, all 8 MEDIUM, 4 LOW). It found
**34.24 km** on a 16-core development machine and **48.27 km** on Render's free instance, where the
time-limited search gets less CPU. Six LOW orders are dropped with `Dropped by optimizer: insufficient
capacity`; which six can vary between runs because several LOW sets of equal count fit. Because the
penalties count orders, OR-Tools serves more (lighter) LOW orders than greedy, which prefers heavier
ones. `SampleDatasetIntegrationTest` asserts, for both strategies, that all 12 HIGH orders are assigned,
no vehicle is over capacity, assigned + unassigned = 30, every unassigned order is LOW, and OR-Tools is
not longer than greedy.
