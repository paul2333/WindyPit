
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

    /**
     * 执行一个 region 任务。
     *
     * <p>【2026-10-24 修复：单个任务抛异常会永久杀死该 world 的 owner 线程】</p>
     *
     * <p>原实现直接 {@code t.execute()}，没有任何 try/catch；而 {@link #loop()} 的
     * try 只捕获 {@link InterruptedException}。于是 region 工作线程里任一任务抛出的
     * {@code RuntimeException}/{@code Error} 会穿过 {@code loop()} 直接终结该线程。</p>
     *
     * <p>后果是<b>静默的</b>：线程死了以后，{@code stopRequested} 仍是 false、队列仍能写入，
     * 所以 {@code RegionScheduler.schedule()} 照常 {@code scheduled.increment()} 并返回
     * 一个 RegionTask —— 但任务永远不会被执行。要等到 64 深度的队列被填满，
     * 才开始抛 {@code RejectedTaskException}。生产表现就是"world-owner-N 线程消失、
     * 那个世界的实体彻底不更新"，而日志里只有一条第一次的堆栈。</p>
     *
     * <p>对比同包的 {@code ComputePool#execute} 是有 {@code catch (Throwable)} 的。</p>
     */
    private void runTask(RegionTask t) {
        SchedulerDiagnostics.RegionCounters c = diag.countersFor(region.id());
        final boolean ran;
        try {
            ran = t.execute();
        } catch (Throwable failure) {
            // 一个坏任务不能带走整个 region 的执行线程。
            c.failed.increment();
            try {
                ThePitThreadLog.failure(region, failure);
            } catch (Throwable loggingFailure) {
                // 记录失败绝不能反过来再抛一次。
            }
            return;
        }
        if (ran) {
            c.executed.increment();
        } else {
            c.retired.increment();
        }
    }

    /** 失败日志收口：只在必要时构造字符串，避免热路径开销。 */
    private static final class ThePitThreadLog {
        static void failure(OwnedRegion region, Throwable failure) {
            java.util.logging.Logger.getLogger("WindyPit-Region")
                    .log(java.util.logging.Level.SEVERE,
                            "region task failed on " + region.id() + "; thread kept alive", failure);
        }
    }
}

