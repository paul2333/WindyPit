
package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region ownership scheduler for WindyPit 1.8.8.
 *
 * Guarantees:
 * - one owner execution context per region; same-region tasks run strictly FIFO, exactly once;
 * - every queue is bounded; overflow is a visible rejection, never silent loss;
 * - no fixed Folia region ids, no cached thread pools: dedicated regions get exactly one fixed
 *   worker thread each, pumped regions execute on the caller-declared primary thread;
 * - cross-region delivery goes through {@link #sendImmutable} with deeply immutable payloads;
 * - shutdown drains every queue on its owner thread and rejects new work afterwards.
 */
public final class RegionScheduler {

    public static final class RegionThreadViolationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public RegionThreadViolationException(String message) {
            super(message);
        }
    }

    private final ConcurrentHashMap<String, RegionThread> regions = new ConcurrentHashMap<String, RegionThread>();
    private final ThreadViolationReporter violations;
    private final SchedulerDiagnostics diag;
    private final AtomicLong taskSequences = new AtomicLong();
    private final AtomicLong messageSequences = new AtomicLong();
    private volatile boolean closed;

    public RegionScheduler() {
        this(new ThreadViolationReporter(128), new SchedulerDiagnostics());
    }

    public RegionScheduler(ThreadViolationReporter violations, SchedulerDiagnostics diag) {
        this.violations = violations;
        this.diag = diag;
    }

    public void register(OwnedRegion region) {
        if (closed) {
            throw new RejectedTaskException("scheduler is closed");
        }
        if (regions.containsKey(region.id())) {
            throw new IllegalStateException("region already registered: " + region.id());
        }
        RegionThread rt = new RegionThread(region, diag);
        regions.put(region.id(), rt);
        rt.startDedicated();
    }

    /**
     * Stops one region: no new work is accepted, the dedicated worker finishes its FIFO queue and
     * exits. Pumped regions are drained inline by the caller. Used when a world is unloaded.
     */
    public void unregister(OwnedRegion region) {
        unregister(region, 1000L);
    }

    /**
     * Stops one region and, for a dedicated worker, waits for its FIFO queue to drain and the
     * thread to exit. Throws when the drain deadline expires so accepted work is never reported
     * as drained when it was not.
     */
    public void unregister(OwnedRegion region, long timeoutMs) {
        if (region == null) {
            return;
        }
        final RegionThread rt = regions.remove(region.id());
        if (rt == null) {
            return;
        }
        rt.requestStop();
        if (rt.isPumped()) {
            rt.drainInline();
            return;
        }
        try {
            if (!rt.awaitTermination(Math.max(1L, timeoutMs))) {
                throw new IllegalStateException("region thread did not terminate in time: " + region.id());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while draining region " + region.id(), interrupted);
        }
    }

    /**
     * Stops one region: no new work is accepted, the dedicated worker finishes its FIFO queue and
     * exits. Pumped regions are drained inline by the caller. Used when a world is unloaded.
     */
    public RegionTask schedule(OwnedRegion region, Runnable body) {
        return schedule(region, body, null);
    }

    public RegionTask schedule(OwnedRegion region, Runnable body, TaskRetirementToken token) {
        RegionThread rt = requireRegion(region.id());
        RegionTask task = new RegionTask(taskSequences.incrementAndGet(), region.id(), rt.generation(), body, token);
        SchedulerDiagnostics.RegionCounters c = diag.countersFor(region.id());
        try {
            rt.enqueue(task);
        } catch (RejectedTaskException e) {
            c.rejected.increment();
            throw e;
        }
        c.scheduled.increment();
        return task;
    }

    /**
     * Cross-region delivery: {@code onOwner} runs on the receiving region's owner thread, in
     * FIFO order. The payload must be a deeply immutable {@link ImmutableOwnerMessage}; the
     * structural check runs before the message is enqueued and rejects live NMS references,
     * collections and mutable DTOs. A full queue throws {@link RejectedTaskException} and the
     * body is never executed on the caller/primary thread.
     */
    public <T extends ImmutableOwnerMessage> void sendImmutable(OwnedRegion to, String fromRegionId, T payload,
                                                               java.util.function.Consumer<T> onOwner) {
        if (onOwner == null) {
            throw new IllegalArgumentException("owner consumer required");
        }
        final RegionMessage<T> message = RegionMessage.of(fromRegionId, messageSequences.incrementAndGet(), payload);
        schedule(to, new Runnable() {
            @Override
            public void run() {
                onOwner.accept(message.payload());
            }
        });
    }

    /** Drains a pumped region now; must be called from the primary (owner) thread. */
    public void pumpOnce(OwnedRegion pumpedRegion) {
        requireRegion(pumpedRegion.id()).pumpOnce();
    }

    /** Throws and records a violation when the current thread is not the region's owner. */
    public void assertRegionThread(OwnedRegion region) {
        RegionThread rt = requireRegion(region.id());
        if (!rt.isOwnerThread(Thread.currentThread())) {
            violations.report(ThreadContext.region(region.id()).describe(), Thread.currentThread().getName());
            throw new RegionThreadViolationException(
                "thread " + Thread.currentThread().getName() + " is not the owner of region " + region.id());
        }
    }

    public int queueDepth(OwnedRegion region) {
        return requireRegion(region.id()).queueDepth();
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Stops accepting work, drains every region on its owner thread (dedicated workers finish
     * their FIFO queue then terminate; pumped regions drain inline on the caller) and waits for
     * dedicated threads to exit. Idempotent.
     */
    public void shutdownDrain(long timeoutMs) throws InterruptedException {
        closed = true;
        final List<RegionThread> dedicated = new ArrayList<RegionThread>();
        for (RegionThread rt : regions.values()) {
            rt.requestStop();
            if (rt.isPumped()) {
                rt.drainInline();
            } else {
                dedicated.add(rt);
            }
        }
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        for (RegionThread rt : dedicated) {
            long remaining = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            if (!rt.awaitTermination(remaining)) {
                throw new IllegalStateException("region thread did not terminate in time: " + rt.regionId());
            }
        }
    }

    public ThreadViolationReporter violations() {
        return violations;
    }

    public SchedulerDiagnostics diagnostics() {
        return diag;
    }

    public String ownerThreadName(OwnedRegion region) {
        RegionThread rt = regions.get(region.id());
        if (rt == null) {
            return null;
        }
        return rt.ownerThreadName();
    }

    private RegionThread requireRegion(String id) {
        RegionThread rt = regions.get(id);
        if (rt == null) {
            throw new IllegalStateException("region not registered: " + id);
        }
        return rt;
    }
}

