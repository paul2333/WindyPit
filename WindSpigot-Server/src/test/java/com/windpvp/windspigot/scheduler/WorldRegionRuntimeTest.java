package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.Test;

/**
 * Production owner-domain tests (W1-REGION-REAL). These exercise the real
 * {@link WorldRegionRuntime} with the real {@link RegionScheduler}, not a mock scheduler.
 *
 * <p>The concurrency test injects a barrier into the planning function: if the two worlds did not
 * run on two independent owner threads at the same time, the barrier would time out and the test
 * would fail. The result is still consumed through the real applier path.</p>
 */
public class WorldRegionRuntimeTest {

    private static final class RecordingApplier implements WorldRegionRuntime.PlanApplier {
        final ConcurrentLinkedQueue<String> appliedWorlds = new ConcurrentLinkedQueue<String>();
        final ConcurrentLinkedQueue<Long> appliedGenerations = new ConcurrentLinkedQueue<Long>();
        final ConcurrentLinkedQueue<String> appliedThreads = new ConcurrentLinkedQueue<String>();
        volatile boolean result = true;

        @Override
        public boolean apply(String worldName, TrackerVisibilityPlanner.Plan plan) {
            appliedWorlds.add(worldName);
            appliedGenerations.add(plan.generation());
            appliedThreads.add(Thread.currentThread().getName());
            return result;
        }
    }

    private static final class PlanningSample {
        final String worldName;
        final String threadName;
        final long startNanos;
        final long durationNanos;

        PlanningSample(String worldName, String threadName, long startNanos, long durationNanos) {
            this.worldName = worldName;
            this.threadName = threadName;
            this.startNanos = startNanos;
            this.durationNanos = durationNanos;
        }

        long endNanos() {
            return startNanos + durationNanos;
        }
    }

    private static TrackerSnapshot snapshot(long generation, String regionId, int entities, int viewers) {
        final List<TrackerSnapshot.EntityView> es = new ArrayList<TrackerSnapshot.EntityView>();
        for (int i = 0; i < entities; i++) {
            es.add(new TrackerSnapshot.EntityView(1000 + i, 1, i, 64, 0, 0f, 0f, false, 64, false));
        }
        final List<TrackerSnapshot.ViewerView> vs = new ArrayList<TrackerSnapshot.ViewerView>();
        for (int i = 0; i < viewers; i++) {
            vs.add(new TrackerSnapshot.ViewerView(i + 1, 0, 64, 0, 64));
        }
        return new TrackerSnapshot(generation, regionId, es, vs);
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

    @Test
    public void twoWorldDomainsPlanConcurrentlyOnDistinctFixedThreads() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final CountDownLatch planned = new CountDownLatch(2);
        final ConcurrentLinkedQueue<PlanningSample> samples = new ConcurrentLinkedQueue<PlanningSample>();
        final RecordingApplier applier = new RecordingApplier();

        final WorldRegionRuntime runtime = new WorldRegionRuntime(scheduler,
                new WorldRegionRuntime.PlanFunction() {
                    @Override
                    public TrackerVisibilityPlanner.Plan plan(TrackerSnapshot current, TrackerSnapshot previous) {
                        try {
                            // Both owner threads must reach this point at the same time.
                            barrier.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException("owner domains did not run concurrently", e);
                        }
                        return new TrackerVisibilityPlanner.Plan(current.generation(), current.regionId(),
                                Collections.<Integer, List<Integer>>emptyMap(),
                                Collections.<Integer, List<Integer>>emptyMap(),
                                Collections.<Integer>emptyList(), Collections.<Integer>emptyList(),
                                Collections.<Integer>emptyList());
                    }
                },
                applier, 2, 4);
        runtime.setPlanningObserver(new WorldRegionRuntime.PlanningObserver() {
            @Override
            public void onPlanComputed(String worldName, String ownerThreadName, long startedNanos, long durationNanos) {
                samples.add(new PlanningSample(worldName, ownerThreadName, startedNanos, durationNanos));
                planned.countDown();
            }
        });
        try {
            runtime.registerWorld("alpha");
            runtime.registerWorld("beta");
            runtime.submitSnapshot("alpha", snapshot(1L, "world-alpha", 4, 2));
            runtime.submitSnapshot("beta", snapshot(1L, "world-beta", 4, 2));

            assertTrue("both owner domains must finish planning",
                    planned.await(10, TimeUnit.SECONDS));
            assertEquals(2, samples.size());

            String alphaThread = null;
            String betaThread = null;
            for (PlanningSample s : samples) {
                if ("alpha".equals(s.worldName)) {
                    alphaThread = s.threadName;
                } else if ("beta".equals(s.worldName)) {
                    betaThread = s.threadName;
                }
            }
            assertNotNull(alphaThread);
            assertNotNull(betaThread);
            assertFalse("worlds must not share one owner thread", alphaThread.equals(betaThread));
            assertTrue(alphaThread.startsWith("WindyPit-Region-world-owner-"));
            assertTrue(betaThread.startsWith("WindyPit-Region-world-owner-"));

            assertEquals(1, runtime.applyPending("alpha"));
            assertEquals(1, runtime.applyPending("beta"));
            assertEquals(2, runtime.appliedTotal());
            assertEquals(2, applier.appliedWorlds.size());
            // Plans were computed off-thread but applied on the caller (primary) thread.
            for (String thread : applier.appliedThreads) {
                assertEquals(Thread.currentThread().getName(), thread);
            }
        } finally {
            runtime.shutdownDrain(5000);
        }
    }

    @Test
    public void plansForOneWorldAreFifoAndAppliedExactlyOnce() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final RecordingApplier applier = new RecordingApplier();
        final WorldRegionRuntime runtime = new WorldRegionRuntime(scheduler,
                new TrackerVisibilityPlanner(null, null), applier, 1, 8);
        try {
            runtime.registerWorld("arena-world");
            for (long gen = 1; gen <= 5; gen++) {
                runtime.submitSnapshot("arena-world", snapshot(gen, "world-arena-world", 2, 1));
            }
            await("five plans", 5000, new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return runtime.stats().get(0).planned() >= 5;
                }
            });
            assertEquals(5, runtime.applyPending("arena-world"));
            assertEquals(5, applier.appliedGenerations.size());
            long expected = 1L;
            for (Long generation : applier.appliedGenerations) {
                assertEquals(expected++, generation.longValue());
            }
            // Exactly once: a second drain applies nothing.
            assertEquals(0, runtime.applyPending("arena-world"));
            assertEquals(5, applier.appliedGenerations.size());
            // Application happens on the caller/primary thread, never on the owner thread.
            for (String thread : applier.appliedThreads) {
                assertFalse(thread.startsWith("WindyPit-Region-"));
            }
        } finally {
            runtime.shutdownDrain(5000);
        }
    }

    @Test
    public void staleGenerationIsDroppedAndCounted() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final RecordingApplier applier = new RecordingApplier();
        final WorldRegionRuntime runtime = new WorldRegionRuntime(scheduler,
                new TrackerVisibilityPlanner(null, null), applier, 1, 8);
        try {
            runtime.registerWorld("w");
            runtime.submitSnapshot("w", snapshot(2L, "world-w", 1, 1));
            await("first plan", 5000, new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return runtime.stats().get(0).planned() >= 1;
                }
            });
            assertEquals(1, runtime.applyPending("w"));

            runtime.submitSnapshot("w", snapshot(1L, "world-w", 1, 1));
            await("stale plan", 5000, new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return runtime.stats().get(0).planned() >= 2;
                }
            });
            assertEquals("stale plan must not be applied", 0, runtime.applyPending("w"));
            assertTrue("stale drop must be visible", runtime.droppedTotal() >= 1);
            assertEquals(1, applier.appliedGenerations.size());
        } finally {
            runtime.shutdownDrain(5000);
        }
    }

    @Test
    public void shutdownDrainsOwnersAndRejectsNewWork() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final RecordingApplier applier = new RecordingApplier();
        final WorldRegionRuntime runtime = new WorldRegionRuntime(scheduler,
                new TrackerVisibilityPlanner(null, null), applier, 1, 4);
        runtime.registerWorld("w");
        runtime.submitSnapshot("w", snapshot(1L, "world-w", 1, 1));
        await("plan", 5000, new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return runtime.stats().get(0).planned() >= 1;
            }
        });
        runtime.shutdownDrain(5000);
        assertTrue(runtime.isClosed());
        runtime.submitSnapshot("w", snapshot(2L, "world-w", 1, 1));
        assertEquals(0, runtime.applyPending("w"));
        assertFalse(runtime.isRegistered("w"));
    }

    @Test
    public void configuredDomainBudgetIsHonouredAndOwnerNamesAreStable() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final ConcurrentLinkedQueue<String> threads = new ConcurrentLinkedQueue<String>();
        final WorldRegionRuntime runtime = new WorldRegionRuntime(scheduler,
                new WorldRegionRuntime.PlanFunction() {
                    @Override
                    public TrackerVisibilityPlanner.Plan plan(TrackerSnapshot current, TrackerSnapshot previous) {
                        threads.add(Thread.currentThread().getName());
                        return new TrackerVisibilityPlanner.Plan(current.generation(), current.regionId(),
                                Collections.<Integer, List<Integer>>emptyMap(),
                                Collections.<Integer, List<Integer>>emptyMap(),
                                Collections.<Integer>emptyList(), Collections.<Integer>emptyList(),
                                Collections.<Integer>emptyList());
                    }
                },
                new RecordingApplier(), 1, 4);
        try {
            runtime.registerWorld("a");
            runtime.registerWorld("b");
            assertEquals(1, runtime.domainCount());
            runtime.submitSnapshot("a", snapshot(1L, "world-a", 1, 1));
            runtime.submitSnapshot("b", snapshot(1L, "world-b", 1, 1));
            await("two plans", 5000, new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return threads.size() >= 2;
                }
            });
            final String first = threads.poll();
            final String second = threads.poll();
            assertEquals("one configured domain must own both worlds", first, second);
            final String owner = runtime.ownerThreadName("a");
            assertNotNull(owner);
            assertEquals(owner, runtime.ownerThreadName("b"));
        } finally {
            runtime.shutdownDrain(5000);
        }
    }
}
