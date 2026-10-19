
package com.windpvp.windspigot.scheduler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async tracker engine (W2-TRACKER): submits visibility planning to the bounded compute pool and
 * delivers plans to the owning region with generation/sequence staleness checks. Stale plans are
 * dropped by the compute pool delivery path and never reach the owner.
 */
public final class TrackerAsyncEngine {

    private final ComputePool pool;
    private final AtomicLong sequences = new AtomicLong();

    public TrackerAsyncEngine(ComputePool pool) {
        this.pool = pool;
    }

    public void submitPlanning(final OwnedRegion region,
                               final TrackerVisibilityPlanner planner,
                               final TrackerSnapshot current,
                               final TrackerSnapshot previous,
                               final Map<Integer, Set<Integer>> previousVisible,
                               final java.util.function.Consumer<TrackerVisibilityPlanner.Plan> onOwner) {
        if (region == null || planner == null || current == null || onOwner == null) {
            throw new IllegalArgumentException("region, planner, current snapshot and onOwner are required");
        }
        final long sequence = sequences.incrementAndGet();
        pool.submit(new ComputePool.ComputeJob<TrackerVisibilityPlanner.Plan>(
                region,
                current.generation(),
                -1,
                sequence,
                new Callable<TrackerVisibilityPlanner.Plan>() {
                    @Override
                    public TrackerVisibilityPlanner.Plan call() {
                        return planner.plan(current, previous, previousVisible);
                    }
                },
                new java.util.function.Consumer<TrackerVisibilityPlanner.Plan>() {
                    @Override
                    public void accept(TrackerVisibilityPlanner.Plan plan) {
                        onOwner.accept(plan);
                    }
                }));
    }
}

