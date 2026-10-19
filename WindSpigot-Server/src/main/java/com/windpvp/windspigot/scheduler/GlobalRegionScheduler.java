
package com.windpvp.windspigot.scheduler;

import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global-region scheduler: non-region world work (console, commands, world-level maintenance)
 * executes on the primary server thread. Immediate tasks are a bounded FIFO drained by
 * {@link #tick()}; delayed and fixed-rate tasks are tick-driven with a bounded pending set.
 */
public final class GlobalRegionScheduler {

    public static final class FixedRateHandle {
        private volatile boolean cancelled;
        final long periodTicks;
        final Runnable body;

        FixedRateHandle(long periodTicks, Runnable body) {
            this.periodTicks = periodTicks;
            this.body = body;
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final class DelayedTask implements Comparable<DelayedTask> {
        final long dueTick;
        final long seq;
        final Runnable body;
        final FixedRateHandle handle;

        DelayedTask(long dueTick, long seq, Runnable body, FixedRateHandle handle) {
            this.dueTick = dueTick;
            this.seq = seq;
            this.body = body;
            this.handle = handle;
        }

        @Override
        public int compareTo(DelayedTask o) {
            int c = Long.compare(dueTick, o.dueTick);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    public static final String GLOBAL_REGION_ID = "global";

    private final ArrayBlockingQueue<Runnable> immediate;
    private final PriorityQueue<DelayedTask> delayed = new PriorityQueue<DelayedTask>();
    private final Object delayedLock = new Object();
    private final int delayedCapacity;
    private final SchedulerDiagnostics diag;
    private final AtomicLong currentTick = new AtomicLong();
    private final AtomicLong seq = new AtomicLong();
    private volatile boolean closed;

    public GlobalRegionScheduler(int immediateCapacity, int delayedCapacity, SchedulerDiagnostics diag) {
        this.immediate = new ArrayBlockingQueue<Runnable>(immediateCapacity);
        this.delayedCapacity = delayedCapacity;
        this.diag = diag;
    }

    public void schedule(Runnable body) {
        offerImmediate(body);
    }

    public void scheduleDelayed(Runnable body, long delayTicks) {
        if (closed) {
            throw new RejectedTaskException("global scheduler is closed");
        }
        SchedulerDiagnostics.RegionCounters c = diag.countersFor(GLOBAL_REGION_ID);
        synchronized (delayedLock) {
            if (delayed.size() >= delayedCapacity) {
                c.rejected.increment();
                throw new RejectedTaskException("global delayed queue full (capacity " + delayedCapacity + ")");
            }
            delayed.add(new DelayedTask(currentTick.get() + Math.max(0L, delayTicks), seq.incrementAndGet(), body, null));
        }
        c.scheduled.increment();
    }

    public FixedRateHandle scheduleAtFixedRate(Runnable body, long initialDelayTicks, long periodTicks) {
        if (closed) {
            throw new RejectedTaskException("global scheduler is closed");
        }
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("periodTicks must be positive");
        }
        SchedulerDiagnostics.RegionCounters c = diag.countersFor(GLOBAL_REGION_ID);
        FixedRateHandle handle = new FixedRateHandle(periodTicks, body);
        synchronized (delayedLock) {
            if (delayed.size() >= delayedCapacity) {
                c.rejected.increment();
                throw new RejectedTaskException("global delayed queue full (capacity " + delayedCapacity + ")");
            }
            delayed.add(new DelayedTask(currentTick.get() + Math.max(0L, initialDelayTicks), seq.incrementAndGet(), body, handle));
        }
        c.scheduled.increment();
        return handle;
    }

    /** Advances one tick on the primary thread: fires due delayed tasks, then drains immediate FIFO. */
    public void tick() {
        final long now = currentTick.incrementAndGet();
        SchedulerDiagnostics.RegionCounters c = diag.countersFor(GLOBAL_REGION_ID);
        while (true) {
            final DelayedTask dt;
            synchronized (delayedLock) {
                DelayedTask head = delayed.peek();
                if (head == null || head.dueTick > now) {
                    break;
                }
                dt = delayed.poll();
            }
            if (dt.handle != null && dt.handle.cancelled) {
                c.retired.increment();
                continue;
            }
            dt.body.run();
            c.executed.increment();
            if (dt.handle != null && !dt.handle.cancelled && dt.handle.periodTicks > 0) {
                if (closed) {
                    c.retired.increment();
                } else {
                    synchronized (delayedLock) {
                        if (delayed.size() >= delayedCapacity) {
                            c.rejected.increment();
                            // Stop re-scheduling; fixed-rate task is dropped due to capacity
                        } else {
                            delayed.add(new DelayedTask(now + dt.handle.periodTicks, seq.incrementAndGet(), dt.handle.body, dt.handle));
                        }
                    }
                }
            }
        }
        Runnable r;
        while ((r = immediate.poll()) != null) {
            r.run();
            c.executed.increment();
        }
    }

    /**
     * Shutdown drain: stop accepting, execute all pending immediate tasks on the caller (owner)
     * thread, drop not-yet-due delayed tasks as retired.
     */
    public void shutdownDrainInline() {
        closed = true;
        SchedulerDiagnostics.RegionCounters c = diag.countersFor(GLOBAL_REGION_ID);
        Runnable r;
        while ((r = immediate.poll()) != null) {
            r.run();
            c.executed.increment();
        }
        synchronized (delayedLock) {
            DelayedTask dt;
            while ((dt = delayed.poll()) != null) {
                c.retired.increment();
            }
        }
    }

    private void offerImmediate(Runnable body) {
        if (closed) {
            throw new RejectedTaskException("global scheduler is closed");
        }
        SchedulerDiagnostics.RegionCounters c = diag.countersFor(GLOBAL_REGION_ID);
        if (!immediate.offer(body)) {
            c.rejected.increment();
            throw new RejectedTaskException("global immediate queue full");
        }
        c.scheduled.increment();
    }

    public long currentTick() {
        return currentTick.get();
    }

    public int immediateDepth() {
        return immediate.size();
    }

    public int delayedDepth() {
        synchronized (delayedLock) {
            return delayed.size();
        }
    }

    public boolean isClosed() {
        return closed;
    }
}

