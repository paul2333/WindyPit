package com.windpvp.windspigot.performance;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/** Bounded tick-duration and CPU sampling for the /tps command. */
public final class TickPerformanceMonitor {
    private static final int DEFAULT_TICK_CAPACITY = 20 * 60 * 15;
    public static final int CPU_SAMPLE_PERIOD_SECONDS = 5;
    private static final int DEFAULT_CPU_CAPACITY = (60 * 15) / CPU_SAMPLE_PERIOD_SECONDS;
    private static final com.sun.management.OperatingSystemMXBean OPERATING_SYSTEM_BEAN = findOperatingSystemBean();

    private final long[] tickDurations;
    private int tickIndex;
    private int tickCount;

    private final double[] systemCpuLoads;
    private final double[] processCpuLoads;
    private int cpuIndex;
    private int cpuCount;

    public TickPerformanceMonitor() {
        this(DEFAULT_TICK_CAPACITY, DEFAULT_CPU_CAPACITY);
    }

    TickPerformanceMonitor(int tickCapacity, int cpuCapacity) {
        if (tickCapacity <= 0 || cpuCapacity <= 0) {
            throw new IllegalArgumentException("Sampling capacities must be positive");
        }
        this.tickDurations = new long[tickCapacity];
        this.systemCpuLoads = new double[cpuCapacity];
        this.processCpuLoads = new double[cpuCapacity];
    }

    public synchronized void recordTickDuration(long durationNanos) {
        if (durationNanos <= 0L) {
            return;
        }
        tickDurations[tickIndex] = durationNanos;
        tickIndex = (tickIndex + 1) % tickDurations.length;
        if (tickCount < tickDurations.length) {
            tickCount++;
        }
    }

    public synchronized void recordCpuLoad(double systemLoad, double processLoad) {
        systemCpuLoads[cpuIndex] = validCpuLoad(systemLoad) ? systemLoad : Double.NaN;
        processCpuLoads[cpuIndex] = validCpuLoad(processLoad) ? processLoad : Double.NaN;
        cpuIndex = (cpuIndex + 1) % systemCpuLoads.length;
        if (cpuCount < systemCpuLoads.length) {
            cpuCount++;
        }
    }

    public TickStats tickStats(int requestedSamples) {
        if (requestedSamples <= 0) {
            throw new IllegalArgumentException("Window must contain at least one sample");
        }

        long[] samples;
        synchronized (this) {
            int size = Math.min(requestedSamples, tickCount);
            samples = new long[size];
            int start = (tickIndex - size + tickDurations.length) % tickDurations.length;
            for (int i = 0; i < size; i++) {
                samples[i] = tickDurations[(start + i) % tickDurations.length];
            }
        }

        if (samples.length == 0) {
            return new TickStats(0L, 0.0D, 0.0D, 0.0D, 0L, 0);
        }
        Arrays.sort(samples);
        return new TickStats(samples[0], percentile(samples, 0.5D), percentile(samples, 0.95D),
                percentile(samples, 0.99D), samples[samples.length - 1], samples.length);
    }

    public CpuStats cpuStats(int requestedSamples) {
        if (requestedSamples <= 0) {
            throw new IllegalArgumentException("Window must contain at least one sample");
        }

        double systemTotal = 0.0D;
        double processTotal = 0.0D;
        int systemCount = 0;
        int processCount = 0;
        synchronized (this) {
            int size = Math.min(requestedSamples, cpuCount);
            int start = (cpuIndex - size + systemCpuLoads.length) % systemCpuLoads.length;
            for (int i = 0; i < size; i++) {
                int index = (start + i) % systemCpuLoads.length;
                double systemLoad = systemCpuLoads[index];
                double processLoad = processCpuLoads[index];
                if (Double.isFinite(systemLoad)) {
                    systemTotal += systemLoad;
                    systemCount++;
                }
                if (Double.isFinite(processLoad)) {
                    processTotal += processLoad;
                    processCount++;
                }
            }
        }
        return new CpuStats(systemCount == 0 ? Double.NaN : systemTotal / systemCount * 100.0D,
                processCount == 0 ? Double.NaN : processTotal / processCount * 100.0D,
                systemCount, processCount);
    }

    /** Returns all sampled windows in one immutable diagnostics snapshot. */
    public Snapshot snapshot() {
        return new Snapshot(tickStats(tickDurations.length), cpuStats(systemCpuLoads.length));
    }

    /** Convert a wall-clock CPU window into samples at the configured interval. */
    public static int cpuWindowSamples(int windowSeconds) {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("Window must contain at least one second");
        }
        return Math.max(1, (windowSeconds + CPU_SAMPLE_PERIOD_SECONDS - 1) / CPU_SAMPLE_PERIOD_SECONDS);
    }

    public static double readSystemCpuLoad() {
        return OPERATING_SYSTEM_BEAN == null ? Double.NaN : OPERATING_SYSTEM_BEAN.getSystemCpuLoad();
    }

    public static double readProcessCpuLoad() {
        return OPERATING_SYSTEM_BEAN == null ? Double.NaN : OPERATING_SYSTEM_BEAN.getProcessCpuLoad();
    }

    private static com.sun.management.OperatingSystemMXBean findOperatingSystemBean() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean
                ? (com.sun.management.OperatingSystemMXBean) bean : null;
    }

    private static boolean validCpuLoad(double load) {
        return Double.isFinite(load) && load >= 0.0D && load <= 1.0D;
    }

    private static double percentile(long[] sortedSamples, double percentile) {
        if (sortedSamples.length == 1) {
            return sortedSamples[0];
        }
        double position = percentile * (sortedSamples.length - 1);
        int lower = (int) position;
        int upper = Math.min(lower + 1, sortedSamples.length - 1);
        double fraction = position - lower;
        return sortedSamples[lower] + (sortedSamples[upper] - (double) sortedSamples[lower]) * fraction;
    }

    public static final class TickStats {
        private final long minNanos;
        private final double medianNanos;
        private final double p95Nanos;
        private final double p99Nanos;
        private final long maxNanos;
        private final int sampleCount;

        private TickStats(long minNanos, double medianNanos, double p95Nanos, double p99Nanos, long maxNanos, int sampleCount) {
            this.minNanos = minNanos;
            this.medianNanos = medianNanos;
            this.p95Nanos = p95Nanos;
            this.p99Nanos = p99Nanos;
            this.maxNanos = maxNanos;
            this.sampleCount = sampleCount;
        }

        public long getMinNanos() { return minNanos; }
        public double getMedianNanos() { return medianNanos; }
        public double getP95Nanos() { return p95Nanos; }
        public double getP99Nanos() { return p99Nanos; }
        public long getMaxNanos() { return maxNanos; }
        public int getSampleCount() { return sampleCount; }
    }

    public static final class CpuStats {
        private final double systemPercent;
        private final double processPercent;
        private final int systemSamples;
        private final int processSamples;

        private CpuStats(double systemPercent, double processPercent, int systemSamples, int processSamples) {
            this.systemPercent = systemPercent;
            this.processPercent = processPercent;
            this.systemSamples = systemSamples;
            this.processSamples = processSamples;
        }

        public boolean hasSystemLoad() { return systemSamples > 0; }
        public boolean hasProcessLoad() { return processSamples > 0; }
        public double getSystemPercent() { return systemPercent; }
        public double getProcessPercent() { return processPercent; }
    }

    public record Snapshot(TickStats ticks, CpuStats cpu) {
        public Snapshot {
            if (ticks == null || cpu == null) throw new NullPointerException("snapshot values");
        }
    }
}
