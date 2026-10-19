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
    /**
     * Exact accepted-task count. {@link ThreadPoolExecutor#getTaskCount()} is documented as
     * approximate and can briefly lag a task that already ran, which made diagnostics/tests
     * observe completed=1, submitted=0. This counter is incremented before submission and is
     * therefore the authoritative "accepted work" figure.
     */
    private final AtomicLong acceptedTasks = new AtomicLong();

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
        // Workers register themselves in beforeExecute: a worker that never picked up a task
        // can never call back into this pool, so the isOnWorker guard is complete without a
        // wrapping ThreadFactory (which cannot be built before the super constructor runs).
        prestartAllCoreThreads();
    }

    /**
     * Records each worker the first time it runs a task.
     *
     * <p>This backs {@link #isOnWorker(Thread)}: a worker that submits work back to its own
     * pool and waits on the result starves the pool, so the caller must detect that and run
     * inline instead. A worker that has never executed a task cannot make that call, so
     * first-task registration is sufficient.
     */
    @Override
    protected void beforeExecute(Thread worker, Runnable task) {
        recordWorker(worker);
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
                getCompletedTaskCount(), acceptedTasks.get(),
                saturationEvents.get(), rejectedTasks.get(), isShutdown());
    }

    /**
     * True when the given thread is one of this pool's workers.
     *
     * <p>Used to refuse fan-out patterns where a worker would submit work back to its own pool
     * and wait on the result: that is a guaranteed starvation deadlock. Workers are created by
     * this pool's {@link java.util.concurrent.ThreadFactory}, so the thread it created is
     * recorded directly.
     */
    public boolean isOnWorker(Thread thread) {
        return thread != null && workerThreads.contains(thread);
    }

    private final java.util.Set<Thread> workerThreads = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<Thread, Boolean>());

    private void recordWorker(Thread worker) {
        if (worker != null) {
            workerThreads.add(worker);
        }
    }

    /**
     * Counts every accepted submission before the worker can run it, and rolls the count back
     * when the executor rejects the submission. {@link ThreadPoolExecutor#getTaskCount()} is
     * documented as approximate and can lag an already-running task, so it is not used for the
     * diagnostics "submitted" figure.
     */
    @Override
    public void execute(Runnable command) {
        acceptedTasks.incrementAndGet();
        try {
            super.execute(command);
        } catch (RuntimeException rejected) {
            acceptedTasks.decrementAndGet();
            throw rejected;
        }
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
