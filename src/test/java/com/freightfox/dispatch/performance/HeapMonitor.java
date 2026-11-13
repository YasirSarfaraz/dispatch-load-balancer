package com.freightfox.dispatch.performance;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures Java heap usage while a scenario runs, in two complementary ways.
 *
 * <ul>
 *   <li><b>Sampled peak</b> (the number in the results table): a daemon thread reads
 *       {@link MemoryMXBean#getHeapMemoryUsage()} every {@value #SAMPLE_INTERVAL_MS} ms and keeps
 *       the maximum "used" value. It is a true point-in-time total, but can miss a spike shorter
 *       than the sampling interval.</li>
 *   <li><b>Pool peak sum</b> (an upper bound): every heap pool's peak is reset with
 *       {@link MemoryPoolMXBean#resetPeakUsage()} at the start, and the peaks are summed at the
 *       end. The JVM tracks these peaks exactly, but eden, survivor and old gen peak at different
 *       moments, so the sum over-estimates the real peak.</li>
 * </ul>
 *
 * <p>{@link #start()} first requests a GC so the baseline is the live heap (Spring context, connection pool
 * data of earlier work already deleted). "Used" includes garbage not yet collected, so the
 * figures depend on the collector and the heap size (-Xmx); they are indicative, not exact.
 */
public final class HeapMonitor implements AutoCloseable {

    /** Sampling period of the background thread. */
    public static final long SAMPLE_INTERVAL_MS = 5;

    private static final double BYTES_PER_MB = 1024.0 * 1024.0;
    private static final long GC_SETTLE_MS = 100;

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final List<MemoryPoolMXBean> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP && pool.isValid())
            .toList();
    private final AtomicLong maxUsed = new AtomicLong();
    private final long baselineBytes;
    private final Thread sampler;
    private volatile boolean running = true;
    private HeapStats result;

    private HeapMonitor() {
        System.gc();
        sleepQuietly(GC_SETTLE_MS);
        baselineBytes = memory.getHeapMemoryUsage().getUsed();
        maxUsed.set(baselineBytes);
        heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);
        sampler = new Thread(this::sampleLoop, "heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    /** Collects garbage, records the baseline and starts sampling. */
    public static HeapMonitor start() {
        return new HeapMonitor();
    }

    /** Stops sampling (idempotent) and returns the measurements. */
    public synchronized HeapStats stop() {
        if (result == null) {
            running = false;
            sampler.interrupt();
            try {
                sampler.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sample();
            long poolPeakSum = heapPools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
            result = new HeapStats(toMb(baselineBytes), toMb(maxUsed.get()), toMb(poolPeakSum));
        }
        return result;
    }

    @Override
    public void close() {
        stop();
    }

    private void sampleLoop() {
        while (running) {
            sample();
            sleepQuietly(SAMPLE_INTERVAL_MS);
        }
    }

    private void sample() {
        long used = memory.getHeapMemoryUsage().getUsed();
        maxUsed.accumulateAndGet(used, Math::max);
    }

    private static double toMb(long bytes) {
        return bytes / BYTES_PER_MB;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @param baselineMb    heap used right after the initial GC
     * @param sampledPeakMb maximum sampled heap used during the scenario (includes the baseline)
     * @param poolPeakSumMb sum of the per-pool peaks (upper bound, includes the baseline)
     */
    public record HeapStats(double baselineMb, double sampledPeakMb, double poolPeakSumMb) {
    }
}
