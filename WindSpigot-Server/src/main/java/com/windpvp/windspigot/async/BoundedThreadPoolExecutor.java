package com.windpvp.windspigot.async;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed worker pool with a hard queue bound. When saturated, submission is
 * rejected instead of running work on the submitting thread. Call
 * {@link #snapshot()} from diagnostics code, not from a per-tick hot path.
 */
public final class BoundedThreadPoolExecutor extends ThreadPoolExecutor {

    private final AtomicLong saturationEvents;
    private final AtomicLong rejectedTasks;

    public BoundedThreadPoolExecutor(int workerCount, int queueCapacity, ThreadFactory threadFactory) {
        this(workerCount, queueCapacity, threadFactory, new AtomicLong(), new AtomicLong());
    }

    private BoundedThreadPoolExecutor(int workerCount, int queueCapacity, ThreadFactory threadFactory,
            AtomicLong saturationEvents, AtomicLong rejectedTasks) {
        super(requirePositive(workerCount, "workerCount"), workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(requirePositive(queueCapacity, "queueCapacity")),
                Objects.requireNonNull(threadFactory, "threadFactory"), rejectOnSaturation(saturationEvents, rejectedTasks));
        this.saturationEvents = saturationEvents;
        this.rejectedTasks = rejectedTasks;
        prestartAllCoreThreads();
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static RejectedExecutionHandler rejectOnSaturation(final AtomicLong saturationEvents,
            final AtomicLong rejectedTasks) {
        return new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
                boolean shutdown = executor.isShutdown();
                if (!shutdown) {
                    saturationEvents.incrementAndGet();
                }
                rejectedTasks.incrementAndGet();
                throw new RejectedExecutionException(shutdown ? "executor is shut down" : "executor queue is full");
            }
        };
    }

    /** Returns an on-demand point-in-time snapshot; no work is scheduled. */
    public Metrics snapshot() {
        return new Metrics(getPoolSize(), getActiveCount(), getQueue().size(),
                getQueue().size() + getQueue().remainingCapacity(),
                getCompletedTaskCount(), getTaskCount(),
                saturationEvents.get(), rejectedTasks.get(), isShutdown());
    }

    /**
     * Offers work without ever executing it on the caller. This is used for
     * optional background work such as bot path searches where a saturated
     * main thread must keep its tick budget instead of running the calculation
     * inline. A false result is an explicit, observable drop.
     */
    public boolean tryExecute(Runnable task) {
        Objects.requireNonNull(task, "task");
        try {
            // Route through ThreadPoolExecutor so taskCount/completedTaskCount and
            // worker lifecycle accounting remain correct. Core workers are
            // prestarted, so an accepted task is never run by the caller.
            execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    public static final class Metrics {
        private final int workerCount;
        private final int activeWorkers;
        private final int queuedTasks;
        private final int queueCapacity;
        private final long completedTasks;
        private final long submittedTasks;
        private final long saturationEvents;
        private final long rejectedTasks;
        private final boolean shutdown;

        private Metrics(int workerCount, int activeWorkers, int queuedTasks, int queueCapacity,
                long completedTasks, long submittedTasks, long saturationEvents, long rejectedTasks, boolean shutdown) {
            this.workerCount = workerCount;
            this.activeWorkers = activeWorkers;
            this.queuedTasks = queuedTasks;
            this.queueCapacity = queueCapacity;
            this.completedTasks = completedTasks;
            this.submittedTasks = submittedTasks;
            this.saturationEvents = saturationEvents;
            this.rejectedTasks = rejectedTasks;
            this.shutdown = shutdown;
        }

        public int getWorkerCount() { return workerCount; }
        public int getActiveWorkers() { return activeWorkers; }
        public int getQueuedTasks() { return queuedTasks; }
        public int getQueueCapacity() { return queueCapacity; }
        public long getCompletedTasks() { return completedTasks; }
        public long getSubmittedTasks() { return submittedTasks; }
        public long getSaturationEvents() { return saturationEvents; }
        public long getRejectedTasks() { return rejectedTasks; }
        public boolean isShutdown() { return shutdown; }
    }
}
