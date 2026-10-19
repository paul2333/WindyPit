
package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregated diagnostics snapshot (W4-DIAGNOSTICS). Combines scheduler, compute, network,
 * thread-violation and region counters into one immutable report for /windypit-tps and
 * monitoring export. No new instrumentation - reads the live counters of existing components.
 */
public final class WindyPitDiagnostics {

    public static final class RegionReport {
        private final String regionId;
        private final long scheduled;
        private final long executed;
        private final long rejected;
        private final long retired;
        private final int maxQueueDepth;

        public RegionReport(String regionId, long scheduled, long executed, long rejected, long retired, int maxQueueDepth) {
            this.regionId = regionId;
            this.scheduled = scheduled;
            this.executed = executed;
            this.rejected = rejected;
            this.retired = retired;
            this.maxQueueDepth = maxQueueDepth;
        }

        public String regionId() { return regionId; }
        public long scheduled() { return scheduled; }
        public long executed() { return executed; }
        public long rejected() { return rejected; }
        public long retired() { return retired; }
        public int maxQueueDepth() { return maxQueueDepth; }
    }

    public static final class ComputeReport {
        private final long submitted;
        private final long executed;
        private final long failed;
        private final long rejected;
        private final long retiredQueued;
        private final long staleDropped;
        private final long deliveryRejected;

        public ComputeReport(long submitted, long executed, long failed, long rejected, long retiredQueued, long staleDropped, long deliveryRejected) {
            this.submitted = submitted;
            this.executed = executed;
            this.failed = failed;
            this.rejected = rejected;
            this.retiredQueued = retiredQueued;
            this.staleDropped = staleDropped;
            this.deliveryRejected = deliveryRejected;
        }

        public long submitted() { return submitted; }
        public long executed() { return executed; }
        public long failed() { return failed; }
        public long rejected() { return rejected; }
        public long retiredQueued() { return retiredQueued; }
        public long staleDropped() { return staleDropped; }
        public long deliveryRejected() { return deliveryRejected; }
    }

    public static final class NetworkReport {
        private final long totalEnqueued;
        private final long totalDropped;
        private final long totalFlushed;
        private final int attachedPlayers;

        public NetworkReport(long totalEnqueued, long totalDropped, long totalFlushed, int attachedPlayers) {
            this.totalEnqueued = totalEnqueued;
            this.totalDropped = totalDropped;
            this.totalFlushed = totalFlushed;
            this.attachedPlayers = attachedPlayers;
        }

        public long totalEnqueued() { return totalEnqueued; }
        public long totalDropped() { return totalDropped; }
        public long totalFlushed() { return totalFlushed; }
        public int attachedPlayers() { return attachedPlayers; }
    }

    private final long capturedAtNanos;
    private final long tickCount;
    private final long threadViolations;
    private final double tps;
    private final double tickP50Millis;
    private final double tickP95Millis;
    private final double tickP99Millis;
    private final List<RegionReport> regions;
    private final ComputeReport compute;
    private final NetworkReport network;

    private WindyPitDiagnostics(long capturedAtNanos, long tickCount, long threadViolations,
                                double tps, double tickP50Millis, double tickP95Millis, double tickP99Millis,
                                List<RegionReport> regions, ComputeReport compute, NetworkReport network) {
        this.capturedAtNanos = capturedAtNanos;
        this.tickCount = tickCount;
        this.threadViolations = threadViolations;
        this.tps = tps;
        this.tickP50Millis = tickP50Millis;
        this.tickP95Millis = tickP95Millis;
        this.tickP99Millis = tickP99Millis;
        this.regions = Collections.unmodifiableList(new ArrayList<RegionReport>(regions));
        this.compute = compute;
        this.network = network;
    }

    public long capturedAtNanos() { return capturedAtNanos; }
    public long tickCount() { return tickCount; }
    public long threadViolations() { return threadViolations; }
    public double tps() { return tps; }
    public double tickP50Millis() { return tickP50Millis; }
    public double tickP95Millis() { return tickP95Millis; }
    public double tickP99Millis() { return tickP99Millis; }
    public List<RegionReport> regions() { return regions; }
    public ComputeReport compute() { return compute; }
    public NetworkReport network() { return network; }

    /** Computes TPS from a tick-duration ring buffer (nanos). */
    public static double computeTps(long[] tickDurationsNanos, int tickCount, long tickIntervalNanos) {
        if (tickCount <= 0 || tickDurationsNanos.length == 0) {
            return 20.0D;
        }
        long totalNanos = 0;
        int samples = Math.min(tickCount, tickDurationsNanos.length);
        for (int i = 0; i < samples; i++) {
            totalNanos += Math.max(tickDurationsNanos[i], tickIntervalNanos);
        }
        if (totalNanos <= 0) {
            return 20.0D;
        }
        double avgNanos = (double) totalNanos / samples;
        return Math.min(20.0D, tickIntervalNanos / avgNanos * 20.0D);
    }

    /** Builds a report from live component references. */
    public static WindyPitDiagnostics snapshot(
            long tickCount,
            long threadViolations,
            double tps,
            double tickP50Millis, double tickP95Millis, double tickP99Millis,
            List<RegionReport> regions,
            ComputeReport compute,
            NetworkReport network) {
        return new WindyPitDiagnostics(System.nanoTime(), tickCount, threadViolations,
                tps, tickP50Millis, tickP95Millis, tickP99Millis, regions, compute, network);
    }
}

