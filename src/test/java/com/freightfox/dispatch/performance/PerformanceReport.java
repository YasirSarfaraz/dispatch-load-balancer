package com.freightfox.dispatch.performance;

import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.performance.cvrplib.CvrplibRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects the measurements of every performance test class run in this JVM and renders them to
 * {@code docs/PERFORMANCE.md} (overwritten).
 *
 * <p>Each performance test class calls {@link #write()} in its {@code @AfterAll}. Surefire runs
 * all test classes in one forked JVM, so this process-wide singleton accumulates the results of
 * every class, and the last {@code write()} produces the complete file. Running only one class
 * (e.g. {@code -Dtest=...}) still writes a valid file containing that class's sections.
 */
public final class PerformanceReport {

    /** Relative location of the generated file under the project directory. */
    public static final String REPORT_PATH = "docs/PERFORMANCE.md";

    private static final Logger log = LoggerFactory.getLogger(PerformanceReport.class);
    private static final PerformanceReport INSTANCE = new PerformanceReport();
    private static final String NEWLINE = "\n";
    private static final String DASH = "-";
    private static final long MILLIS_PER_SECOND = 1_000;

    private final Map<String, Map<String, String>> settings = new LinkedHashMap<>();
    private final List<ScenarioResult> scenarios = new ArrayList<>();
    private final List<ComparisonRow> comparisons = new ArrayList<>();
    private final List<CvrplibRow> cvrplibRows = new ArrayList<>();
    private final List<TimeLimitRow> timeLimitRows = new ArrayList<>();
    private WarmUpResult warmUp;
    private String database;

    private PerformanceReport() {
    }

    public static PerformanceReport get() {
        return INSTANCE;
    }

    /** Adds or replaces a named block of settings (rendered as a table). */
    public synchronized void putSettings(String section, Map<String, String> values) {
        settings.put(section, new LinkedHashMap<>(values));
    }

    public synchronized void setDatabase(String database) {
        this.database = database;
    }

    public synchronized void setWarmUp(WarmUpResult warmUp) {
        this.warmUp = warmUp;
    }

    public synchronized void addScenario(ScenarioResult result) {
        scenarios.add(result);
    }

    public synchronized void addComparison(ComparisonRow row) {
        comparisons.add(row);
    }

    public synchronized void addTimeLimitRow(TimeLimitRow row) {
        timeLimitRows.add(row);
    }

    public synchronized void addCvrplib(CvrplibRow row) {
        cvrplibRows.add(row);
    }

    /** Renders everything collected so far and overwrites {@value #REPORT_PATH}. */
    public synchronized Path write() {
        Path file = Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).resolve(REPORT_PATH);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, render(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
        log.info("Performance report written to {}", file.toAbsolutePath());
        return file;
    }

    /** The Markdown document (package-visible for inspection). */
    synchronized String render() {
        StringBuilder md = new StringBuilder();
        md.append("# Performance Report").append(NEWLINE).append(NEWLINE);
        md.append("Generated: ").append(ZonedDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append(NEWLINE).append(NEWLINE);
        md.append("> This file is written automatically by the `@Tag(\"performance\")` tests in ")
                .append("`src/test/java/com/freightfox/dispatch/performance`. Do not edit it by hand; ")
                .append("re-run the benchmarks instead.").append(NEWLINE).append(NEWLINE);
        md.append("**How to reproduce:** `./mvnw verify -Pperformance` (Windows: `.\\mvnw.cmd verify -Pperformance`).")
                .append(NEWLINE).append(NEWLINE);

        renderKeyValues(md, "Machine", MachineInfo.describe(database));
        md.append("## Test settings").append(NEWLINE).append(NEWLINE);
        settings.forEach((section, values) -> renderKeyValueTable(md, "### " + section, values));

        renderResults(md);
        renderDetails(md);
        renderWarmUp(md);
        renderComparison(md);
        renderPriorityCheck(md);
        renderTimeLimitSensitivity(md);
        renderCvrplib(md);
        renderNotes(md);
        return md.toString();
    }

    private static void renderKeyValues(StringBuilder md, String title, Map<String, String> values) {
        renderKeyValueTable(md, "## " + title, values);
    }

    private static void renderKeyValueTable(StringBuilder md, String heading, Map<String, String> values) {
        md.append(heading).append(NEWLINE).append(NEWLINE);
        md.append("| Item | Value |").append(NEWLINE).append("|---|---|").append(NEWLINE);
        values.forEach((key, value) -> md.append("| ").append(key).append(" | ").append(escape(value)).append(" |")
                .append(NEWLINE));
        md.append(NEWLINE);
    }

    private void renderResults(StringBuilder md) {
        md.append("## Results").append(NEWLINE).append(NEWLINE);
        if (scenarios.isEmpty()) {
            md.append("_The dispatch scenarios were not run in this execution._").append(NEWLINE).append(NEWLINE);
            return;
        }
        md.append("| Strategy | Orders x Vehicles | Upsert ms | Plan ms | Total distance km | Assigned | Unassigned "
                + "| Peak heap MB |").append(NEWLINE);
        md.append("|---|---|---:|---:|---:|---:|---:|---:|").append(NEWLINE);
        for (ScenarioResult r : scenarios) {
            md.append("| ").append(r.strategy())
                    .append(" | ").append(size(r.orders(), r.vehicles()))
                    .append(" | ").append(integer(r.upsertMs()))
                    .append(" | ").append(integer(r.planMs()))
                    .append(" | ").append(decimal(r.totalDistanceKm()))
                    .append(" | ").append(integer(r.assigned()))
                    .append(" | ").append(integer(r.unassigned()))
                    .append(" | ").append(megabytes(r.heap().sampledPeakMb()))
                    .append(" |").append(NEWLINE);
        }
        md.append(NEWLINE);
        md.append("- **Upsert ms**: `VehicleService.upsertVehicles` + `OrderService.upsertOrders` (the real services, ")
                .append("H2 in-memory, chunked findAllById + saveAll with JDBC batching), on empty tables, so every row is ")
                .append("an INSERT.").append(NEWLINE);
        md.append("- **Plan ms**: the strategy bean's `plan(orders, vehicles)` alone, on the entities loaded from the ")
                .append("database with `findAllOrders()` / `findAllVehicles()`. End-to-end `DispatchService.generatePlan` ")
                .append("times are in the details table.").append(NEWLINE);
        md.append("- **Total distance km**: sum of the open-route distances (vehicle location -> stops, no return), ")
                .append("Haversine.").append(NEWLINE);
        md.append("- **Peak heap MB**: highest sampled heap use during the scenario (generate + upsert + load + plan ")
                .append("+ end-to-end), including the Spring context baseline. See methodology below.").append(NEWLINE)
                .append(NEWLINE);
    }

    private void renderDetails(StringBuilder md) {
        if (scenarios.isEmpty()) {
            return;
        }
        md.append("### Details").append(NEWLINE).append(NEWLINE);
        md.append("| Strategy | Orders x Vehicles | Upsert vehicles ms | Upsert orders ms | End-to-end generatePlan ms "
                + "| Order weight / fleet capacity | Assigned H / M / L | Unassigned H / M / L | Heap baseline MB "
                + "| Heap pool-peak sum MB |").append(NEWLINE);
        md.append("|---|---|---:|---:|---:|---|---|---|---:|---:|").append(NEWLINE);
        for (ScenarioResult r : scenarios) {
            md.append("| ").append(r.strategy())
                    .append(" | ").append(size(r.orders(), r.vehicles()))
                    .append(" | ").append(integer(r.upsertVehiclesMs()))
                    .append(" | ").append(integer(r.upsertOrdersMs()))
                    .append(" | ").append(integer(r.endToEndMs()))
                    .append(" | ").append(integer(r.totalWeight())).append(" / ").append(integer(r.totalCapacity()))
                    .append(" | ").append(byPriority(r.assignedByPriority()))
                    .append(" | ").append(byPriority(r.unassignedByPriority()))
                    .append(" | ").append(megabytes(r.heap().baselineMb()))
                    .append(" | ").append(megabytes(r.heap().poolPeakSumMb()))
                    .append(" |").append(NEWLINE);
        }
        md.append(NEWLINE);
        md.append("The generator draws weights 1-50 (mean 25.5) and capacities 100-500 (mean 300), so whenever the ")
                .append("orders outnumber the vehicles by more than about 12 to 1 the total order weight exceeds the ")
                .append("fleet capacity (see the weight / capacity column) and many unassigned orders are expected. ")
                .append("They are LOW and MEDIUM first because HIGH orders are placed before any other priority.")
                .append(NEWLINE).append(NEWLINE);
    }

    private void renderWarmUp(StringBuilder md) {
        if (warmUp == null) {
            return;
        }
        md.append("### JIT warm-up (greedy)").append(NEWLINE).append(NEWLINE);
        md.append("Before the timed greedy scenarios, the greedy strategy planned an in-memory dataset of ")
                .append(size(warmUp.orders(), warmUp.vehicles())).append(" ").append(warmUp.iterations())
                .append(" times (same seed):").append(NEWLINE).append(NEWLINE);
        md.append("| Run | Plan ms |").append(NEWLINE).append("|---|---:|").append(NEWLINE);
        md.append("| Cold (first plan in this JVM) | ").append(integer(warmUp.coldMs())).append(" |").append(NEWLINE);
        md.append("| Warm (fastest of the following runs) | ").append(integer(warmUp.warmMs())).append(" |")
                .append(NEWLINE).append(NEWLINE);
        md.append("A first upsert of ").append(size(warmUp.upsertOrders(), warmUp.upsertVehicles()))
                .append(" (vehicles + orders) took ").append(integer(warmUp.coldUpsertMs()))
                .append(" ms (Hibernate/JDBC code paths cold); the tables were then cleared, so the scenario upsert ")
                .append("times are warm.").append(NEWLINE).append(NEWLINE);
        if (warmUp.nativeLoadMs() > 0) {
            md.append("The OR-Tools native libraries were loaded once before the OR-Tools scenarios in ")
                    .append(integer(warmUp.nativeLoadMs())).append(" ms");
        } else {
            md.append("The OR-Tools native libraries had already been loaded in this JVM by an earlier test class ")
                    .append("(the load time is listed in that class's test settings above)");
        }
        md.append(", so that one-off cost is not part of any OR-Tools plan time below.").append(NEWLINE).append(NEWLINE);
    }

    private void renderComparison(StringBuilder md) {
        md.append("## Greedy vs OR-Tools (same datasets)").append(NEWLINE).append(NEWLINE);
        if (comparisons.isEmpty()) {
            md.append("_Not run in this execution._").append(NEWLINE).append(NEWLINE);
            return;
        }
        md.append("| Orders x Vehicles | OR-Tools km | Greedy km | OR-Tools vs greedy distance | OR-Tools assigned "
                + "(H / M / L) | Greedy assigned (H / M / L) | OR-Tools km per assigned order "
                + "| Greedy km per assigned order | OR-Tools plan ms | Greedy plan ms |").append(NEWLINE);
        md.append("|---|---:|---:|---:|---|---|---:|---:|---:|---:|").append(NEWLINE);
        for (ComparisonRow c : comparisons) {
            double deltaPercent = c.greedyKm() == 0 ? 0 : 100.0 * (c.orToolsKm() - c.greedyKm()) / c.greedyKm();
            md.append("| ").append(size(c.orders(), c.vehicles()))
                    .append(" | ").append(decimal(c.orToolsKm()))
                    .append(" | ").append(decimal(c.greedyKm()))
                    .append(" | ").append(String.format(Locale.ROOT, "%+.1f%%", deltaPercent))
                    .append(" | ").append(integer(c.orToolsAssigned())).append(" (")
                    .append(byPriority(c.orToolsAssignedByPriority())).append(")")
                    .append(" | ").append(integer(c.greedyAssigned())).append(" (")
                    .append(byPriority(c.greedyAssignedByPriority())).append(")")
                    .append(" | ").append(perOrder(c.orToolsKm(), c.orToolsAssigned()))
                    .append(" | ").append(perOrder(c.greedyKm(), c.greedyAssigned()))
                    .append(" | ").append(integer(c.orToolsPlanMs()))
                    .append(" | ").append(integer(c.greedyPlanMs()))
                    .append(" |").append(NEWLINE);
        }
        md.append(NEWLINE);
        md.append("Both strategies ran on the same entities loaded from the database. When the assigned counts ")
                .append("differ, total distance alone is not a like-for-like comparison (serving more orders needs more ")
                .append("driving); km per assigned order is shown as a rough normaliser. OR-Tools' drop penalties are ")
                .append("lexicographic by priority and count orders, not weight, so it maximises the number of served ")
                .append("orders per priority level before it minimises distance. Greedy places heavier orders first ")
                .append("within a priority.").append(NEWLINE).append(NEWLINE);
    }

    /**
     * Flags OR-Tools results that left HIGH orders unassigned while serving MEDIUM or LOW ones,
     * citing greedy's HIGH count on the same data as proof that more HIGH orders fit.
     */
    private void renderPriorityCheck(StringBuilder md) {
        List<String> findings = new ArrayList<>();
        for (ScenarioResult r : scenarios) {
            if (!"ortools".equals(r.strategy())) {
                continue;
            }
            int unassignedHigh = r.unassignedByPriority().getOrDefault(Priority.HIGH, 0);
            int assignedLower = r.assignedByPriority().getOrDefault(Priority.MEDIUM, 0)
                    + r.assignedByPriority().getOrDefault(Priority.LOW, 0);
            if (unassignedHigh > 0 && assignedLower > 0) {
                StringBuilder finding = new StringBuilder();
                finding.append("- **").append(size(r.orders(), r.vehicles())).append("**: OR-Tools left ")
                        .append(integer(unassignedHigh)).append(" HIGH orders unassigned while serving ")
                        .append(integer(assignedLower)).append(" MEDIUM/LOW orders.");
                comparisons.stream()
                        .filter(c -> c.orders() == r.orders() && c.vehicles() == r.vehicles())
                        .findFirst()
                        .ifPresent(c -> finding.append(" Greedy served ")
                                .append(integer(c.greedyAssignedByPriority().getOrDefault(Priority.HIGH, 0)))
                                .append(" HIGH orders on the same data versus OR-Tools' ")
                                .append(integer(c.orToolsAssignedByPriority().getOrDefault(Priority.HIGH, 0)))
                                .append(", so more HIGH orders fit at the same time: the solver did not reach the ")
                                .append("lexicographic optimum its drop penalties encode within the time limit."));
                findings.add(finding.toString());
            }
        }
        md.append("### Priority check (OR-Tools)").append(NEWLINE).append(NEWLINE);
        if (findings.isEmpty()) {
            md.append("No OR-Tools result left a HIGH order unassigned while serving a lower priority.")
                    .append(NEWLINE).append(NEWLINE);
            return;
        }
        findings.forEach(f -> md.append(f).append(NEWLINE));
        md.append(NEWLINE);
        md.append("This should not happen: OR-Tools is warm-started from the greedy plan and guarded against ")
                .append("dropping more HIGH/MEDIUM/LOW orders than greedy. Investigate the OR-Tools strategy logs.")
                .append(NEWLINE).append(NEWLINE);
    }

    private void renderTimeLimitSensitivity(StringBuilder md) {
        if (timeLimitRows.isEmpty()) {
            return;
        }
        md.append("### OR-Tools time-limit sensitivity (1,000 x 50, same data)").append(NEWLINE).append(NEWLINE);
        md.append("| Time limit | Strategy | Assigned | Assigned H / M / L | Unassigned H / M / L | Total distance km "
                + "| Plan ms |").append(NEWLINE);
        md.append("|---:|---|---:|---|---|---:|---:|").append(NEWLINE);
        for (TimeLimitRow row : timeLimitRows) {
            md.append("| ").append(integer(row.timeLimitMs() / MILLIS_PER_SECOND)).append(" s")
                    .append(" | ").append(row.strategy())
                    .append(" | ").append(integer(row.assigned()))
                    .append(" | ").append(byPriority(row.assignedByPriority()))
                    .append(" | ").append(byPriority(row.unassignedByPriority()))
                    .append(" | ").append(decimal(row.totalDistanceKm()))
                    .append(" | ").append(integer(row.planMs()))
                    .append(" |").append(NEWLINE);
        }
        md.append(NEWLINE);
        md.append("The configured limit is the first row (the scenario above); the others use a strategy instance built ")
                .append("with the production constructor and only the time limit changed. Because the objective is ")
                .append("lexicographic (served HIGH, then MEDIUM, then LOW, then distance) and the search starts from ")
                .append("the greedy plan, a longer limit can only keep or improve the priority mix; with the mix ")
                .append("unchanged, the extra time goes into shorter routes.").append(NEWLINE).append(NEWLINE);
    }

    private void renderCvrplib(StringBuilder md) {
        md.append("## CVRPLIB benchmark (X instances)").append(NEWLINE).append(NEWLINE);
        if (cvrplibRows.isEmpty()) {
            md.append("_Not run in this execution._").append(NEWLINE).append(NEWLINE);
            return;
        }
        md.append("| Instance | Customers | Q | k | BKS routes | Fleet given | Best-known cost | Strategy | Served "
                + "| Routes used | CVRPLIB cost (closed tours) | Gap vs best-known | Open-route cost (no return legs) "
                + "| Plan ms |").append(NEWLINE);
        md.append("|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|").append(NEWLINE);
        for (CvrplibRow r : cvrplibRows) {
            String gap = String.format(Locale.ROOT, "%+.1f%%", r.gapPercent())
                    + (r.allServed() ? "" : " (not comparable: " + (r.customers() - r.served()) + " dropped)");
            md.append("| ").append(r.instance())
                    .append(" | ").append(integer(r.customers()))
                    .append(" | ").append(integer(r.capacity()))
                    .append(" | ").append(r.k())
                    .append(" | ").append(r.bksRoutes())
                    .append(" | ").append(r.fleet())
                    .append(" | ").append(integer(r.bestKnownCost()))
                    .append(" | ").append(r.strategy())
                    .append(" | ").append(r.served()).append(" / ").append(r.customers())
                    .append(" | ").append(r.routesUsed())
                    .append(" | ").append(integer(r.closedCost()))
                    .append(" | ").append(gap)
                    .append(" | ").append(integer(r.openCost()))
                    .append(" | ").append(integer(r.planMs()))
                    .append(" |").append(NEWLINE);
        }
        md.append(NEWLINE);
        md.append("""
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

                """);
    }

    private static void renderNotes(StringBuilder md) {
        md.append("""
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
                """);
    }

    private static String size(int orders, int vehicles) {
        return integer(orders) + " x " + integer(vehicles);
    }

    private static String integer(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static String decimal(double value) {
        return String.format(Locale.US, "%,.2f", value);
    }

    private static String megabytes(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }

    private static String perOrder(double km, int assigned) {
        return assigned == 0 ? DASH : String.format(Locale.US, "%,.3f", km / assigned);
    }

    private static String byPriority(Map<Priority, Integer> counts) {
        return integer(counts.getOrDefault(Priority.HIGH, 0)) + " / "
                + integer(counts.getOrDefault(Priority.MEDIUM, 0)) + " / "
                + integer(counts.getOrDefault(Priority.LOW, 0));
    }

    private static String escape(String value) {
        return value == null ? DASH : value.replace("|", "\\|");
    }
}
