
package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded pure-compute executor (W2-COMPUTE). Fixed worker threads over a bounded queue; every
 * job carries regionId/generation/entityId/sequence and its result is delivered on the owning
 * region's owner thread through the region scheduler. Results whose staleness check fails are
 * dropped and counted - stale compute never reaches the owner.
 *
 * No cached pools, no unbounded queues. Compute failures are counted and never delivered.
 */
public final class ComputePool {

    /** Staleness decision made on the owner thread at delivery time. */
    public interface StaleCheck {
        boolean isCurrent(String regionId, long generation, int entityId, long sequence);
    }

    /** One unit of pure compute; work must not touch entities, worlds or Bukkit state. */
    public static final class ComputeJob<T> {
        private final OwnedRegion region;
        private final long generation;
        private final int entityId;
        private final long sequence;
        private final Callable<T> work;
        private final java.util.function.Consumer<T> onOwner;

        public ComputeJob(OwnedRegion region, long generation, int entityId, long sequence, Callable<T> work, java.util.function.Consumer<T> onOwner) {
            if (region == null || work == null || onOwner == null) {
                throw new IllegalArgumentException("region, work and onOwner are required");
            }
            this.region = region;
            this.generation = generation;
            this.entityId = entityId;
            this.sequence = sequence;
            this.work = work;
            this.onOwner = onOwner;
        }

        public OwnedRegion region() {
            return region;
        }

        public long generation() {
            return generation;
        }

        public int entityId() {
            return entityId;
        }

        public long sequence() {
            return sequence;
        }

        public Callable<T> work() {
            return work;
        }

        public java.util.function.Consumer<T> onOwner() {
            return onOwner;
        }
    }

    public static final class Stats {
        private final long submitted;
        private final long executed;
        private final long failed;
        private final long rejected;
        private final long retiredQueued;
        private final long staleDropped;
        private final long deliveryRejected;

        public Stats(long submitted, long executed, long failed, long rejected, long retiredQueued, long staleDropped, long deliveryRejected) {
            this.submitted = submitted;
            this.executed = executed;
            this.failed = failed;
            this.rejected = rejected;
            this.retiredQueued = retiredQueued;
            this.staleDropped = staleDropped;
            this.deliveryRejected = deliveryRejected;
        }

        public long submitted() {
            return submitted;
        }

        public long executed() {
            return executed;
        }

        public long failed() {
            return failed;
        }

        public long rejected() {
            return rejected;
        }

        public long retiredQueued() {
            return retiredQueued;
        }

        public long staleDropped() {
            return staleDropped;
        }

        public long deliveryRejected() {
            return deliveryRejected;
        }
    }

    private final BlockingQueue<Runnable> queue;
    private volatile Thread[] workers;
    private final RegionScheduler scheduler;
    private final StaleCheck staleCheck;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong retiredQueued = new AtomicLong();
    private final AtomicLong staleDropped = new AtomicLong();
    private final AtomicLong deliveryRejected = new AtomicLong();

    private final int threadCount;
    private final int queueCapacity;
    private final Object workerLock = new Object();
    private volatile boolean workersStarted;

    public ComputePool(int threads, int queueCapacity, RegionScheduler scheduler, StaleCheck staleCheck) {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queue capacity must be positive");
        }
        this.queue = new ArrayBlockingQueue<Runnable>(queueCapacity);
        this.scheduler = scheduler;
        this.staleCheck = staleCheck;
        this.threadCount = threads;
        this.queueCapacity = queueCapacity;
        this.workers = new Thread[0];
    }

    /**
     * Starts the worker threads on first use.
     *
     * <p>The pool is created during server boot on every mode, but the only production callers of
     * {@link #submit} are optional features. Starting N/2 threads that never receive a job costs
     * memory and a wake-up per idle timeout for no benefit, so the workers are created lazily and
     * stay absent entirely while the pool is unused.
     */
    private void startWorkersIfNeeded() {
        if (workersStarted) {
            return;
        }
        synchronized (workerLock) {
            if (workersStarted) {
                return;
            }
            final Thread[] created = new Thread[threadCount];
            for (int i = 0; i < threadCount; i++) {
                final int id = i;
                created[i] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        loop();
                    }
                }, "WindyPit-Compute-" + id);
                created[i].setDaemon(true);
            }
            this.workers = created;
            workersStarted = true;
            for (Thread worker : created) {
                worker.start();
            }
        }
    }

    /** True once at least one job has forced the worker threads to start. */
    public boolean isStarted() {
        return workersStarted;
    }

    /** Configured worker count (0 while the pool has never been used). */
    public int workerCount() {
        return workers.length;
    }

    public <T> void submit(final ComputeJob<T> job) {
        if (closed.get()) {
            rejected.incrementAndGet();
            throw new RejectedTaskException("compute pool is closed");
        }
        startWorkersIfNeeded();
        if (!queue.offer(new Runnable() {
            @Override
            public void run() {
                execute(job);
            }
        })) {
            rejected.incrementAndGet();
            throw new RejectedTaskException("compute queue full");
        }
        submitted.incrementAndGet();
    }

    private <T> void execute(final ComputeJob<T> job) {
        T result;
        try {
            result = job.work().call();
            executed.incrementAndGet();
        } catch (Throwable t) {
            failed.incrementAndGet();
            return;
        }
        final T r = result;
        try {
            scheduler.schedule(job.region(), new Runnable() {
                @Override
                public void run() {
                    if (staleCheck.isCurrent(job.region().id(), job.generation(), job.entityId(), job.sequence())) {
                        job.onOwner().accept(r);
                    } else {
                        staleDropped.incrementAndGet();
                    }
                }
            });
        } catch (RejectedTaskException e) {
            deliveryRejected.incrementAndGet();
        }
    }

    /** Stops accepting, discards queued (not started) jobs, workers exit after the current job. */
    public void closeDiscardQueued() {
        if (closed.getAndSet(true)) {
            return;
        }
        final List<Runnable> drained = new ArrayList<Runnable>();
        queue.drainTo(drained);
        retiredQueued.addAndGet(drained.size());
    }

    public boolean awaitTermination(long timeoutMs) throws InterruptedException {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        for (Thread w : workers) {
            final long remaining = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            w.join(remaining);
            if (w.isAlive()) {
                return false;
            }
        }
        return true;
    }

    public void shutdownDrain(long timeoutMs) throws InterruptedException {
        closeDiscardQueued();
        if (!awaitTermination(timeoutMs)) {
            throw new IllegalStateException("compute workers did not terminate in time");
        }
    }

    private void loop() {
        try {
            while (true) {
                Runnable r = queue.poll(50L, TimeUnit.MILLISECONDS);
                if (r == null) {
                    if (closed.get()) {
                        return;
                    }
                    continue;
                }
                r.run();
                if (closed.get() && queue.isEmpty()) {
                    return;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public Stats stats() {
        return new Stats(submitted.get(), executed.get(), failed.get(), rejected.get(), retiredQueued.get(), staleDropped.get(), deliveryRejected.get());
    }

    public boolean isClosed() {
        return closed.get();
    }

    public int queuedCount() {
        return queue.size();
    }
}

