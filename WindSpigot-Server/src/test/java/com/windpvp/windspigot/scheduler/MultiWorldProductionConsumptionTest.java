package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.junit.Test;

/**
 * Production-consumption proof for two independent non-arena world owners (Prompt 70).
 *
 * <p>Unlike a barrier-only test, this exercises the real {@link TrackerVisibilityPlanner} on two
 * immutable world snapshots, records the fixed owner thread plus start/end of each planning work
 * item, and consumes the resulting plans into per-world visible-entity state through a
 * {@link WorldRegionRuntime.PlanApplier}. A second tick proves that adds and removes are really
 * consumed, not just counted.</p>
 */
public class MultiWorldProductionConsumptionTest {

    private static final class WorkRecord {
        final String worldName;
        final long generation;
        final String ownerThread;
        final long startNanos;
        final long endNanos;
        final List<Integer> added;
        final List<Integer> removed;

        WorkRecord(String worldName, long generation, String ownerThread, long startNanos, long endNanos,
                   List<Integer> added, List<Integer> removed) {
            this.worldName = worldName;
            this.generation = generation;
            this.ownerThread = ownerThread;
            this.startNanos = startNanos;
            this.endNanos = endNanos;
            this.added = added;
            this.removed = removed;
        }

        String workId() {
            return worldName + "#" + generation;
        }
    }

    private static final class RecordingPlanner implements WorldRegionRuntime.PlanFunction {
        private final TrackerVisibilityPlanner delegate;
        final List<WorkRecord> records = Collections.synchronizedList(new ArrayList<WorkRecord>());

        RecordingPlanner(TrackerVisibilityPlanner delegate) {
            this.delegate = delegate;
        }

        @Override
        public TrackerVisibilityPlanner.Plan plan(TrackerSnapshot current, TrackerSnapshot previous) {
            final long startedNanos = System.nanoTime();
            final TrackerVisibilityPlanner.Plan plan = delegate.plan(current, previous, null);
            final long finishedNanos = System.nanoTime();
            records.add(new WorkRecord(current.regionId(), current.generation(),
                    Thread.currentThread().getName(), startedNanos, finishedNanos,
                    flatten(plan.add()), flatten(plan.remove())));
            return plan;
        }
    }

    private static final class ConsumingApplier implements WorldRegionRuntime.PlanApplier {
        private final Map<String, Set<Integer>> visible = new ConcurrentHashMap<String, Set<Integer>>();
        private final Map<String, List<Long>> appliedGenerations = new ConcurrentHashMap<String, List<Long>>();
        private final Map<String, List<String>> appliedThreads = new ConcurrentHashMap<String, List<String>>();

        @Override
        public boolean apply(String worldName, TrackerVisibilityPlanner.Plan plan) {
            Set<Integer> set = visible.get(worldName);
            if (set == null) {
                set = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
                visible.put(worldName, set);
            }
            for (List<Integer> add : plan.add().values()) {
                set.addAll(add);
            }
            for (List<Integer> remove : plan.remove().values()) {
                set.removeAll(remove);
            }
            list(appliedGenerations, worldName).add(Long.valueOf(plan.generation()));
            list(appliedThreads, worldName).add(Thread.currentThread().getName());
            return true;
        }

        Set<Integer> visibleIn(String worldName) {
            final Set<Integer> set = visible.get(worldName);
            return set == null ? Collections.<Integer>emptySet() : new HashSet<Integer>(set);
        }

        int appliedCount(String worldName) {
            final List<Long> list = appliedGenerations.get(worldName);
            return list == null ? 0 : list.size();
        }

        List<String> appliedThreads(String worldName) {
            final List<String> list = appliedThreads.get(worldName);
            return list == null ? Collections.<String>emptyList() : new ArrayList<String>(list);
        }
    }

    @Test
    public void twoIndependentWorldOwnersProduceAndConsumeVisibility() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final RecordingPlanner planner = new RecordingPlanner(
                new TrackerVisibilityPlanner(TrackerVisibilityPlanner.defaultLowLatencyTypeIds(), null));
        final ConsumingApplier applier = new ConsumingApplier();
        final WorldRegionRuntime runtime = new WorldRegionRuntime(scheduler, planner, applier, 2, 8);
        try {
            runtime.registerWorld("alpha");
            runtime.registerWorld("beta");
            assertEquals(2, runtime.domainCount());

            runtime.submitSnapshot("alpha", alphaSnapshot(1L, 10.0D));
            runtime.submitSnapshot("beta", betaSnapshot(1L, 600.0D));
            await("alpha plan queued", 5000, queuedAtLeast(runtime, "alpha", 1));
            await("beta plan queued", 5000, queuedAtLeast(runtime, "beta", 1));

            assertEquals(1, runtime.applyPending("alpha"));
            assertEquals(1, runtime.applyPending("beta"));

            assertEquals(new HashSet<Integer>(Arrays.asList(Integer.valueOf(1001), Integer.valueOf(1003))),
                    applier.visibleIn("alpha"));
            assertEquals(Collections.singleton(Integer.valueOf(2001)), applier.visibleIn("beta"));
            assertEquals(1, applier.appliedCount("alpha"));
            assertEquals(1, applier.appliedCount("beta"));
            for (String thread : applier.appliedThreads("alpha")) {
                assertEquals("plan application must stay on the primary caller",
                        Thread.currentThread().getName(), thread);
            }

            final String alphaOwner = runtime.ownerThreadName("alpha");
            final String betaOwner = runtime.ownerThreadName("beta");
            assertNotNull(alphaOwner);
            assertNotNull(betaOwner);
            assertTrue(alphaOwner.startsWith("WindyPit-Region-world-owner-"));
            assertTrue(betaOwner.startsWith("WindyPit-Region-world-owner-"));
            assertFalse("two worlds must not share one fixed owner thread", alphaOwner.equals(betaOwner));

            assertEquals(2, planner.records.size());
            final WorkRecord alphaWork = recordFor(planner.records, "world-alpha", 1L);
            final WorkRecord betaWork = recordFor(planner.records, "world-beta", 1L);
            assertNotNull(alphaWork);
            assertNotNull(betaWork);
            assertFalse("work ids must be distinct", alphaWork.workId().equals(betaWork.workId()));
            assertTrue(alphaWork.ownerThread.startsWith("WindyPit-Region-world-owner-"));
            assertTrue(betaWork.ownerThread.startsWith("WindyPit-Region-world-owner-"));
            assertFalse(alphaWork.ownerThread.equals(betaWork.ownerThread));
            assertTrue("work must carry a start/end window", alphaWork.endNanos >= alphaWork.startNanos);
            assertEquals(Arrays.asList(Integer.valueOf(1001), Integer.valueOf(1003)), alphaWork.added);
            assertEquals(Collections.singletonList(Integer.valueOf(2001)), betaWork.added);

            // Exactly once: a second drain must not re-apply the same generation.
            assertEquals(0, runtime.applyPending("alpha"));
            assertEquals(0, runtime.applyPending("beta"));
            assertEquals(1, applier.appliedCount("alpha"));
            assertEquals(1, applier.appliedCount("beta"));
        } finally {
            runtime.shutdownDrain(5000);
        }
    }

    @Test
    public void nextTickConsumesAddsAndRemovesPerWorldExactlyOnce() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final RecordingPlanner planner = new RecordingPlanner(
                new TrackerVisibilityPlanner(TrackerVisibilityPlanner.defaultLowLatencyTypeIds(), null));
        final ConsumingApplier applier = new ConsumingApplier();
        final WorldRegionRuntime runtime = new WorldRegionRuntime(scheduler, planner, applier, 2, 8);
        try {
            runtime.registerWorld("alpha");
            runtime.registerWorld("beta");
            runtime.submitSnapshot("alpha", alphaSnapshot(1L, 10.0D));
            runtime.submitSnapshot("beta", betaSnapshot(1L, 600.0D));
            await("first alpha plan queued", 5000, queuedAtLeast(runtime, "alpha", 1));
            await("first beta plan queued", 5000, queuedAtLeast(runtime, "beta", 1));
            assertEquals(1, runtime.applyPending("alpha"));
            assertEquals(1, runtime.applyPending("beta"));

            // alpha: entity 1001 leaves the viewer range; beta: entity 2002 enters it.
            runtime.submitSnapshot("alpha", alphaSnapshot(2L, 500.0D));
            runtime.submitSnapshot("beta", betaSnapshot(2L, 520.0D));
            await("second alpha plan queued", 5000, queuedAtLeast(runtime, "alpha", 1));
            await("second beta plan queued", 5000, queuedAtLeast(runtime, "beta", 1));
            assertEquals(1, runtime.applyPending("alpha"));
            assertEquals(1, runtime.applyPending("beta"));

            assertEquals(Collections.singleton(Integer.valueOf(1003)), applier.visibleIn("alpha"));
            assertEquals(new HashSet<Integer>(Arrays.asList(Integer.valueOf(2001), Integer.valueOf(2002))),
                    applier.visibleIn("beta"));
            assertEquals(2, applier.appliedCount("alpha"));
            assertEquals(2, applier.appliedCount("beta"));

            final WorkRecord alphaSecond = recordFor(planner.records, "world-alpha", 2L);
            final WorkRecord betaSecond = recordFor(planner.records, "world-beta", 2L);
            assertNotNull(alphaSecond);
            assertNotNull(betaSecond);
            assertEquals("alpha must consume the out-of-range removal",
                    Collections.singletonList(Integer.valueOf(1001)), alphaSecond.removed);
            assertEquals("beta must consume the in-range addition",
                    Collections.singletonList(Integer.valueOf(2002)), betaSecond.added);

            assertEquals(0, runtime.applyPending("alpha"));
            assertEquals(0, runtime.applyPending("beta"));
            assertEquals(2, applier.appliedCount("alpha"));
            assertEquals(2, applier.appliedCount("beta"));
        } finally {
            runtime.shutdownDrain(5000);
        }
    }

    private static TrackerSnapshot alphaSnapshot(long generation, double entity1001X) {
        final List<TrackerSnapshot.EntityView> entities = new ArrayList<TrackerSnapshot.EntityView>();
        entities.add(new TrackerSnapshot.EntityView(1001, 1, entity1001X, 64.0D, 0.0D, 0.0F, 0.0F, false));
        entities.add(new TrackerSnapshot.EntityView(1002, 1, 200.0D, 64.0D, 0.0D, 0.0F, 0.0F, false));
        entities.add(new TrackerSnapshot.EntityView(1003, 1, 0.0D, 64.0D, 10.0D, 0.0F, 0.0F, false));
        final List<TrackerSnapshot.ViewerView> viewers = new ArrayList<TrackerSnapshot.ViewerView>();
        viewers.add(new TrackerSnapshot.ViewerView(1, 0.0D, 64.0D, 0.0D, 64));
        return new TrackerSnapshot(generation, "world-alpha", entities, viewers);
    }

    private static TrackerSnapshot betaSnapshot(long generation, double entity2002X) {
        final List<TrackerSnapshot.EntityView> entities = new ArrayList<TrackerSnapshot.EntityView>();
        entities.add(new TrackerSnapshot.EntityView(2001, 1, 510.0D, 64.0D, 500.0D, 0.0F, 0.0F, false));
        entities.add(new TrackerSnapshot.EntityView(2002, 1, entity2002X, 64.0D, 500.0D, 0.0F, 0.0F, false));
        final List<TrackerSnapshot.ViewerView> viewers = new ArrayList<TrackerSnapshot.ViewerView>();
        viewers.add(new TrackerSnapshot.ViewerView(2, 500.0D, 64.0D, 500.0D, 32));
        return new TrackerSnapshot(generation, "world-beta", entities, viewers);
    }

    private static List<Integer> flatten(Map<Integer, List<Integer>> map) {
        final List<Integer> out = new ArrayList<Integer>();
        for (List<Integer> values : map.values()) {
            out.addAll(values);
        }
        Collections.sort(out);
        return out;
    }

    private static WorkRecord recordFor(List<WorkRecord> records, String worldName, long generation) {
        synchronized (records) {
            for (WorkRecord record : records) {
                if (record.worldName.equals(worldName) && record.generation == generation) {
                    return record;
                }
            }
        }
        return null;
    }

    private static BooleanSupplier queuedAtLeast(final WorldRegionRuntime runtime, final String worldName,
                                                 final int minimum) {
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                for (WorldRegionRuntime.WorldStats stats : runtime.stats()) {
                    if (stats.worldName().equals(worldName)) {
                        return stats.queueDepth() >= minimum;
                    }
                }
                return false;
            }
        };
    }

    private static void await(String what, long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5L);
        }
        fail("timed out waiting for " + what);
    }

    private static <K, V> List<V> list(Map<K, List<V>> map, K key) {
        List<V> list = map.get(key);
        if (list == null) {
            list = Collections.synchronizedList(new ArrayList<V>());
            map.put(key, list);
        }
        return list;
    }
}
