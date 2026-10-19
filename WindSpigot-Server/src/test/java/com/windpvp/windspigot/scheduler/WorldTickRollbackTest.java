package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.windpvp.windspigot.config.WindyPitConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.Test;

/**
 * Rollback contract for the real tick seam (Prompt 70).
 *
 * <p>Two independent rollback layers are asserted:</p>
 * <ul>
 *   <li>configuration rollback flags must disable the REGION owner domains and keep the
 *       synchronous primary path;</li>
 *   <li>an owner plan failure or a rejected plan application must not commit partial state and
 *       must force a full visibility resync on the next tick.</li>
 * </ul>
 *
 * <p>The final test is a source assertion that {@code WorldTicker} still calls
 * {@code WorldServer.doTick()} and {@code tickEntities()} directly on the calling (primary)
 * thread; this run did not move either call.</p>
 */
public class WorldTickRollbackTest {

    @Test
    public void rollbackFlagsDisableWorldOwnerDomains() throws Exception {
        final WindyPitTickIntegration region = new WindyPitTickIntegration(regionConfig(""));
        try {
            assertNotNull("REGION mode must create the owner runtime before rollback", region.worldRegions());
        } finally {
            region.shutdownDrain(5000);
        }

        final WindyPitTickIntegration noRegionScheduler = new WindyPitTickIntegration(
                regionConfig("rollback:\n  disable-region-scheduler: true\n"));
        try {
            assertNull("disable-region-scheduler must fall back to the synchronous path",
                    noRegionScheduler.worldRegions());
        } finally {
            noRegionScheduler.shutdownDrain(5000);
        }

        final WindyPitTickIntegration noAsyncTracking = new WindyPitTickIntegration(
                regionConfig("rollback:\n  disable-async-tracking: true\n"));
        try {
            assertNull("disable-async-tracking must fall back to the synchronous path",
                    noAsyncTracking.worldRegions());
        } finally {
            noAsyncTracking.shutdownDrain(5000);
        }
    }

    @Test
    public void forcePrimaryThreadArenaRollbackKeepsArenaPumped() throws Exception {
        final WindyPitTickIntegration dedicated = new WindyPitTickIntegration(WindyPitConfig.parse(
                "threading:\n  mode: region\n  arena-owner: dedicated\n"));
        try {
            assertEquals(ExecutionMode.DEDICATED_THREAD, dedicated.arena().mode());
        } finally {
            dedicated.shutdownDrain(5000);
        }

        final WindyPitTickIntegration forced = new WindyPitTickIntegration(WindyPitConfig.parse(
                "threading:\n  mode: region\n  arena-owner: dedicated\n"
                        + "rollback:\n  force-primary-thread-arena: true\n"));
        try {
            assertEquals("rollback flag must force the arena back onto the primary pump",
                    ExecutionMode.PUMPED_PRIMARY, forced.arena().mode());
        } finally {
            forced.shutdownDrain(5000);
        }
    }

    @Test
    public void failedOwnerPlanAppliesNothingAndForcesFullResync() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final List<TrackerSnapshot> previousSeen = Collections.synchronizedList(new ArrayList<TrackerSnapshot>());
        final List<Long> appliedGenerations = Collections.synchronizedList(new ArrayList<Long>());
        final AtomicInteger calls = new AtomicInteger();
        final WorldRegionRuntime runtime = new WorldRegionRuntime(scheduler,
                new WorldRegionRuntime.PlanFunction() {
                    @Override
                    public TrackerVisibilityPlanner.Plan plan(TrackerSnapshot current, TrackerSnapshot previous) {
                        previousSeen.add(previous);
                        if (calls.incrementAndGet() == 2) {
                            throw new IllegalStateException("injected owner plan failure");
                        }
                        return emptyPlan(current);
                    }
                },
                new WorldRegionRuntime.PlanApplier() {
                    @Override
                    public boolean apply(String worldName, TrackerVisibilityPlanner.Plan plan) {
                        appliedGenerations.add(Long.valueOf(plan.generation()));
                        return true;
                    }
                }, 1, 8);
        try {
            runtime.registerWorld("w");
            runtime.submitSnapshot("w", snapshot(1L));
            await("first plan", 5000, plannedAtLeast(runtime, 1));
            assertEquals(1, runtime.applyPending("w"));

            runtime.submitSnapshot("w", snapshot(2L));
            await("failed plan", 5000, failedAtLeast(runtime, 1));
            assertEquals("a failed plan must never be applied", 0, runtime.applyPending("w"));

            runtime.submitSnapshot("w", snapshot(3L));
            await("full resync plan", 5000, plannedAtLeast(runtime, 2));
            assertEquals(1, runtime.applyPending("w"));

            assertEquals(Arrays.asList(Long.valueOf(1L), Long.valueOf(3L)), appliedGenerations);
            assertEquals(3, previousSeen.size());
            assertNull("first plan has no previous snapshot", previousSeen.get(0));
            assertNotNull("the failed plan still saw the previous snapshot", previousSeen.get(1));
            assertNull("the plan after a failure must be a full resync", previousSeen.get(2));
        } finally {
            runtime.shutdownDrain(5000);
        }
    }

    @Test
    public void rejectedPlanDoesNotAdvanceAppliedGeneration() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final List<TrackerSnapshot> previousSeen = Collections.synchronizedList(new ArrayList<TrackerSnapshot>());
        final List<Long> appliedGenerations = Collections.synchronizedList(new ArrayList<Long>());
        final WorldRegionRuntime runtime = new WorldRegionRuntime(scheduler,
                new WorldRegionRuntime.PlanFunction() {
                    @Override
                    public TrackerVisibilityPlanner.Plan plan(TrackerSnapshot current, TrackerSnapshot previous) {
                        previousSeen.add(previous);
                        return emptyPlan(current);
                    }
                },
                new WorldRegionRuntime.PlanApplier() {
                    @Override
                    public boolean apply(String worldName, TrackerVisibilityPlanner.Plan plan) {
                        return false;
                    }
                }, 1, 8);
        try {
            runtime.registerWorld("w");
            runtime.submitSnapshot("w", snapshot(1L));
            await("first plan", 5000, plannedAtLeast(runtime, 1));
            assertEquals(0, runtime.applyPending("w"));
            assertEquals(1, runtime.stats().get(0).failed());
            assertEquals(0L, runtime.stats().get(0).appliedGeneration());

            runtime.submitSnapshot("w", snapshot(2L));
            await("second plan", 5000, plannedAtLeast(runtime, 2));
            assertEquals(0, runtime.applyPending("w"));
            assertEquals(2, previousSeen.size());
            assertNull("rejected application must reset the incremental state", previousSeen.get(1));
            assertTrue(appliedGenerations.isEmpty());
        } finally {
            runtime.shutdownDrain(5000);
        }
    }

    @Test
    public void worldTickStillCallsDoTickAndTickEntitiesOnTheCallingThread() throws IOException {
        final String ticker = readWorldSource("WorldTicker.java");
        final int run = ticker.indexOf("public void run()");
        final int doTick = ticker.indexOf("worldserver.doTick();");
        final int tickEntities = ticker.indexOf("worldserver.tickEntities();");
        final int trackerStep = ticker.indexOf("WorldTrackerTick.run(this);");

        assertTrue("WorldTicker.run must exist", run >= 0);
        assertTrue("doTick must be called from WorldTicker.run", doTick > run);
        assertTrue("tickEntities must be called after doTick", tickEntities > doTick);
        assertTrue("the tracker step must stay after tickEntities", trackerStep > tickEntities);
        assertEquals("doTick must be called exactly once", 1, count(ticker, "worldserver.doTick();"));
        assertEquals("tickEntities must be called exactly once", 1, count(ticker, "worldserver.tickEntities();"));
        assertFalse("WorldTicker must not create a thread", ticker.contains("new Thread"));
        assertFalse("WorldTicker must not use an executor", ticker.contains("Executor"));
        assertFalse("WorldTicker must not use a future", ticker.contains("CompletableFuture"));
        assertFalse("WorldTicker must not schedule the world tick", ticker.contains("schedule("));
        assertFalse("WorldTicker must not submit the world tick", ticker.contains("submit("));
    }

    private static WindyPitConfig regionConfig(String extra) throws IOException {
        return WindyPitConfig.parse("threading:\n  mode: region\n  region-threads: 2\n"
                + "features:\n  async-entity-tracking: true\n" + extra);
    }

    private static TrackerSnapshot snapshot(long generation) {
        final List<TrackerSnapshot.EntityView> entities = new ArrayList<TrackerSnapshot.EntityView>();
        entities.add(new TrackerSnapshot.EntityView(1000, 1, 0.0D, 64.0D, 0.0D, 0.0F, 0.0F, false));
        final List<TrackerSnapshot.ViewerView> viewers = new ArrayList<TrackerSnapshot.ViewerView>();
        viewers.add(new TrackerSnapshot.ViewerView(1, 0.0D, 64.0D, 0.0D, 64));
        return new TrackerSnapshot(generation, "world-w", entities, viewers);
    }

    private static TrackerVisibilityPlanner.Plan emptyPlan(TrackerSnapshot current) {
        return new TrackerVisibilityPlanner.Plan(current.generation(), current.regionId(),
                Collections.<Integer, List<Integer>>emptyMap(),
                Collections.<Integer, List<Integer>>emptyMap(),
                Collections.<Integer>emptyList(), Collections.<Integer>emptyList(),
                Collections.<Integer>emptyList());
    }

    private static BooleanSupplier plannedAtLeast(final WorldRegionRuntime runtime, final long planned) {
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return runtime.stats().get(0).planned() >= planned;
            }
        };
    }

    private static BooleanSupplier failedAtLeast(final WorldRegionRuntime runtime, final long failed) {
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return runtime.stats().get(0).failed() >= failed;
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

    private static int count(String haystack, String needle) {
        int found = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            found++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return found;
    }

    private static String readWorldSource(String fileName) throws IOException {
        final Path relative = Paths.get("src", "main", "java", "com", "windpvp", "windspigot", "world", fileName);
        Path directory = Paths.get("").toAbsolutePath();
        for (int level = 0; level < 8 && directory != null; level++) {
            final Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new AssertionError("world source not found: " + relative);
    }
}
