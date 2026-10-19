
package com.windpvp.windspigot.scheduler;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Execution carrier of one owned region. DEDICATED_THREAD regions run a fixed single worker
 * over a bounded FIFO queue; PUMPED_PRIMARY regions are drained by the primary thread at pump
 * points. Both modes guarantee strict FIFO and exactly-once execution.
 */
final class RegionThread {

    private final OwnedRegion region;
    private final ArrayBlockingQueue<RegionTask> queue;
    private final SchedulerDiagnostics diag;
    private volatile Thread worker;
    private volatile Thread ownerThread;
    private volatile boolean stopRequested;
    private final long generation;

    RegionThread(OwnedRegion region, SchedulerDiagnostics diag) {
        this.region = region;
        this.diag = diag;
        this.queue = new ArrayBlockingQueue<RegionTask>(region.queueCapacity());
        this.generation = 1L;
    }

    long generation() {
        return generation;
    }

    String regionId() {
        return region.id();
    }

    boolean isPumped() {
        return region.mode() == ExecutionMode.PUMPED_PRIMARY;
    }

    void enqueue(RegionTask task) {
        if (stopRequested) {
            throw new RejectedTaskException("region " + region.id() + " is draining/closed");
        }
        SchedulerDiagnostics.RegionCounters c = diag.countersFor(region.id());
        c.noteDepth(queue.size() + 1);
        if (!queue.offer(task)) {
            throw new RejectedTaskException("queue full for region " + region.id() + " (capacity " + region.queueCapacity() + ")");
        }
    }

    void startDedicated() {
        if (!isPumped() && worker == null) {
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "WindyPit-Region-" + region.id());
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void loop() {
        ownerThread = Thread.currentThread();
        try {
            while (true) {
                RegionTask t = queue.poll(50L, TimeUnit.MILLISECONDS);
                if (t == null) {
                    if (stopRequested && queue.isEmpty()) {
                        return;
                    }
                    continue;
                }
                runTask(t);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** PUMPED_PRIMARY only: caller thread becomes the owner and drains the queue in FIFO. */
    void pumpOnce() {
        if (!isPumped()) {
            throw new IllegalStateException("region " + region.id() + " is not a pumped region");
        }
        ownerThread = Thread.currentThread();
        RegionTask t;
        while ((t = queue.poll()) != null) {
            runTask(t);
        }
    }

    /** Shutdown drain for pumped regions; caller must be the owner (primary) thread. */
    void drainInline() {
        ownerThread = Thread.currentThread();
        RegionTask t;
        while ((t = queue.poll()) != null) {
            runTask(t);
        }
    }

    void requestStop() {
        stopRequested = true;
    }

    boolean awaitTermination(long timeoutMs) throws InterruptedException {
        Thread w = worker;
        if (w == null) {
            return true;
        }
        w.join(timeoutMs);
        return !w.isAlive();
    }

    boolean isOwnerThread(Thread t) {
        Thread o = ownerThread;
        return o != null && o == t;
    }

    int queueDepth() {
        return queue.size();
    }

    String ownerThreadName() {
        Thread o = ownerThread;
        if (o != null) {
            return o.getName();
        }
        if (isPumped()) {
            return "pumped-primary";
        }
        return null;
    }

    private void runTask(RegionTask t) {
        SchedulerDiagnostics.RegionCounters c = diag.countersFor(region.id());
        boolean ran = t.execute();
        if (ran) {
            c.executed.increment();
        } else {
            c.retired.increment();
        }
    }
}

