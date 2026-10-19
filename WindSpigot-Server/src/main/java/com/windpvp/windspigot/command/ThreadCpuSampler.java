package com.windpvp.windspigot.command;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Background CPU sampler for the {@code /windypit-tps} diagnostics page.
 *
 * <p>Reads {@link ThreadMXBean#getThreadCpuTime(long)} once per second and derives a per-thread
 * CPU percentage from the delta. A percentage of 100 means the thread kept one core busy for the
 * whole window, so {@code cpuPercent / 100} is the equivalent number of cores that thread used.
 * Sampling runs on a daemon thread at minimum priority so the measured workload is not perturbed
 * by the measurement itself.
 */
public final class ThreadCpuSampler {

    private static final ThreadCpuSampler INSTANCE = new ThreadCpuSampler();

    public static ThreadCpuSampler get() {
        return INSTANCE;
    }

    private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
    private final AtomicReference<Snapshot> latest = new AtomicReference<Snapshot>(Snapshot.empty());
    private final Map<Long, Long> previousCpuNanos = new HashMap<Long, Long>();
    private volatile boolean started;
    private volatile boolean cpuTimeEnabled;
    private long previousWallNanos = System.nanoTime();

    private ThreadCpuSampler() {
    }

    /** Starts the sampler once. Safe to call from the server thread on every command invocation. */
    public void start() {
        if (started) {
            return;
        }
        synchronized (this) {
            if (started) {
                return;
            }
            started = true;
            try {
                cpuTimeEnabled = mx.isThreadCpuTimeSupported();
                if (cpuTimeEnabled && !mx.isThreadCpuTimeEnabled()) {
                    mx.setThreadCpuTimeEnabled(true);
                }
            } catch (Throwable unsupported) {
                cpuTimeEnabled = false;
            }
            if (!cpuTimeEnabled) {
                return;
            }
            final Thread sampler = new Thread(new Runnable() {
                @Override
                public void run() {
                    sampleLoop();
                }
            }, "WindyPit-CpuSampler");
            sampler.setDaemon(true);
            sampler.setPriority(Thread.MIN_PRIORITY);
            sampler.start();
        }
    }

    private void sampleLoop() {
        while (true) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                sampleOnce();
            } catch (Throwable ignored) {
                // A failing sample must never take the server down; the next tick retries.
            }
        }
    }

    private void sampleOnce() {
        final long wallNanos = System.nanoTime();
        final long elapsed = wallNanos - previousWallNanos;
        if (elapsed <= 0L) {
            return;
        }
        final ThreadInfo[] infos = mx.dumpAllThreads(false, false);
        final Map<Long, Long> currentCpu = new HashMap<Long, Long>(infos.length * 2);
        final List<Sample> samples = new ArrayList<Sample>(infos.length);
        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;
            }
            final long id = info.getThreadId();
            final long cpuNanos = mx.getThreadCpuTime(id);
            if (cpuNanos < 0L) {
                continue;
            }
            currentCpu.put(id, cpuNanos);
            final Long before = previousCpuNanos.get(id);
            if (before == null) {
                continue;
            }
            final double percent = (cpuNanos - before) * 100.0D / elapsed;
            samples.add(new Sample(info.getThreadName(), id, Math.max(0.0D, percent), info.getThreadState()));
        }
        previousCpuNanos.clear();
        previousCpuNanos.putAll(currentCpu);
        previousWallNanos = wallNanos;
        latest.set(new Snapshot(wallNanos, samples, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Takes a short synchronous measurement when no window is available yet (for example the
     * first diagnostics command right after boot). Blocks the caller for roughly the given gap.
     */
    public synchronized Snapshot sampleBlocking(long gapMillis) {
        if (!cpuTimeEnabled) {
            return latest.get();
        }
        sampleOnce();
        try {
            Thread.sleep(Math.max(50L, gapMillis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return latest.get();
        }
        sampleOnce();
        return latest.get();
    }

    public Snapshot latest() {
        return latest.get();
    }

    public boolean isAvailable() {
        return cpuTimeEnabled;
    }

    /** One thread's utilisation over the last sampling window. */
    public static final class Sample {

        private final String name;
        private final long id;
        private final double cpuPercent;
        private final Thread.State state;

        Sample(String name, long id, double cpuPercent, Thread.State state) {
            this.name = name;
            this.id = id;
            this.cpuPercent = cpuPercent;
            this.state = state;
        }

        public String name() {
            return name;
        }

        public long id() {
            return id;
        }

        public double cpuPercent() {
            return cpuPercent;
        }

        public double cores() {
            return cpuPercent / 100.0D;
        }

        public Thread.State state() {
            return state;
        }
    }

    /** Immutable view of the most recent sampling window. */
    public static final class Snapshot {

        private final long timestampNanos;
        private final List<Sample> samples;
        private final int processors;

        Snapshot(long timestampNanos, List<Sample> samples, int processors) {
            this.timestampNanos = timestampNanos;
            this.samples = Collections.unmodifiableList(samples);
            this.processors = processors;
        }

        static Snapshot empty() {
            return new Snapshot(0L, Collections.<Sample>emptyList(), Runtime.getRuntime().availableProcessors());
        }

        public long timestampNanos() {
            return timestampNanos;
        }

        public List<Sample> samples() {
            return samples;
        }

        public int processors() {
            return processors;
        }

        public boolean isEmpty() {
            return samples.isEmpty();
        }

        public double totalCores() {
            double total = 0.0D;
            for (Sample sample : samples) {
                total += sample.cores();
            }
            return total;
        }
    }
}
