
package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-region scheduling counters plus bounded-queue depth tracking. Snapshot is immutable.
 */
public final class SchedulerDiagnostics {

    public static final class RegionStats {
        private final String regionId;
        private final long scheduled;
        private final long executed;
        private final long rejected;
        private final long retired;
        private final int maxQueueDepth;

        public RegionStats(String regionId, long scheduled, long executed, long rejected, long retired, int maxQueueDepth) {
            this.regionId = regionId;
            this.scheduled = scheduled;
            this.executed = executed;
            this.rejected = rejected;
            this.retired = retired;
            this.maxQueueDepth = maxQueueDepth;
        }

        public String regionId() {
            return regionId;
        }

        public long scheduled() {
            return scheduled;
        }

        public long executed() {
            return executed;
        }

        public long rejected() {
            return rejected;
        }

        public long retired() {
            return retired;
        }

        public int maxQueueDepth() {
            return maxQueueDepth;
        }
    }

    static final class RegionCounters {
        final LongAdder scheduled = new LongAdder();
        final LongAdder executed = new LongAdder();
        final LongAdder rejected = new LongAdder();
        final LongAdder retired = new LongAdder();
        /**
         * 任务抛异常被 {@code RegionThread#runTask} 兜住的次数。
         *
         * <p>【2026-10-24 新增】原本 region 任务抛异常会直接杀死 owner 线程且不留痕迹
         * （loop 的 catch 只接 InterruptedException）。现在异常被捕获并且每次自增 ——
         * 有这个计数才能发现"某个 region 持续失败但线程还活着"。</p>
         */
        final LongAdder failed = new LongAdder();
        volatile int maxQueueDepth;

        void noteDepth(int depth) {
            if (depth > maxQueueDepth) {
                maxQueueDepth = depth;
            }
        }
    }

    private final ConcurrentHashMap<String, RegionCounters> byRegion = new ConcurrentHashMap<String, RegionCounters>();

    RegionCounters countersFor(String regionId) {
        RegionCounters c = byRegion.get(regionId);
        if (c == null) {
            c = byRegion.computeIfAbsent(regionId, k -> new RegionCounters());
        }
        return c;
    }

    public List<RegionStats> snapshotRegionStats() {
        final List<RegionStats> out = new ArrayList<RegionStats>();
        for (ConcurrentHashMap.Entry<String, RegionCounters> e : byRegion.entrySet()) {
            RegionCounters c = e.getValue();
            out.add(new RegionStats(e.getKey(), c.scheduled.sum(), c.executed.sum(), c.rejected.sum(), c.retired.sum(), c.maxQueueDepth));
        }
        Collections.sort(out, (a, b) -> a.regionId().compareTo(b.regionId()));
        return Collections.unmodifiableList(out);
    }

    public RegionStats statsFor(String regionId) {
        RegionCounters c = byRegion.get(regionId);
        if (c == null) {
            return new RegionStats(regionId, 0L, 0L, 0L, 0L, 0);
        }
        return new RegionStats(regionId, c.scheduled.sum(), c.executed.sum(), c.rejected.sum(), c.retired.sum(), c.maxQueueDepth);
    }
}

