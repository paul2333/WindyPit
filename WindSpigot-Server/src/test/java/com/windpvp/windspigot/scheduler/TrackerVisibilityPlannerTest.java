
package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class TrackerVisibilityPlannerTest {

    private TrackerSnapshot.EntityView entity(int id, int type, double x, double y, double z, float yaw, float pitch) {
        return new TrackerSnapshot.EntityView(id, type, x, y, z, yaw, pitch, false);
    }

    private TrackerSnapshot.EntityView entity(int id, int type, double x, double y, double z, float yaw, float pitch, boolean dead) {
        return new TrackerSnapshot.EntityView(id, type, x, y, z, yaw, pitch, dead);
    }

    private TrackerSnapshot.ViewerView viewer(int id, double x, double y, double z, int vd) {
        return new TrackerSnapshot.ViewerView(id, x, y, z, vd);
    }

    @Test
    public void distanceCullingAddsAndOmits() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot snap = new TrackerSnapshot(1L, "arena",
                Arrays.asList(entity(1, 1, 10, 0, 0, 0f, 0f), entity(2, 1, 100, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan plan = p.plan(snap, null, null);
        assertEquals(Collections.singletonList(1), plan.add().get(100));
        assertTrue(plan.lowLatency().isEmpty());
    }

    @Test
    public void lowLatencyTypesBypassDistanceCulling() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(TrackerVisibilityPlanner.defaultLowLatencyTypeIds(), null);
        TrackerSnapshot snap = new TrackerSnapshot(1L, "arena",
                Collections.singletonList(entity(3, 50, 500, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan plan = p.plan(snap, null, null);
        assertEquals(Collections.singletonList(3), plan.add().get(100));
        assertEquals(Collections.singletonList(3), plan.lowLatency());
    }

    @Test
    public void typeDistanceMultiplierScalesRadius() {
        Map<Integer, Double> mult = new HashMap<Integer, Double>();
        mult.put(1, 2.0D);
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, mult);
        TrackerSnapshot snap = new TrackerSnapshot(1L, "arena",
                Collections.singletonList(entity(1, 1, 80, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan plan = p.plan(snap, null, null);
        assertEquals(Collections.singletonList(1), plan.add().get(100));
    }

    @Test
    public void movementAndRotationAreClassified() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot prev = new TrackerSnapshot(1L, "arena",
                Arrays.asList(entity(7, 1, 0, 0, 0, 0f, 0f), entity(8, 1, 40, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerSnapshot cur = new TrackerSnapshot(2L, "arena",
                Arrays.asList(entity(7, 1, 5, 0, 0, 0f, 0f), entity(8, 1, 40, 0, 0, 30f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        Map<Integer, Set<Integer>> prevVisible = new HashMap<Integer, Set<Integer>>();
        prevVisible.put(100, new HashSet<Integer>(Arrays.asList(7, 8)));
        TrackerVisibilityPlanner.Plan plan = p.plan(cur, prev, prevVisible);
        assertTrue(plan.moved().contains(7));
        assertFalse(plan.moved().contains(8));
        assertTrue(plan.rotatedOnly().contains(8));
        assertTrue(plan.add().get(100).isEmpty());
        assertTrue(plan.remove().get(100).isEmpty());
    }

    @Test
    public void deathAndDisappearanceProduceRemovals() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot prev = new TrackerSnapshot(1L, "arena",
                Arrays.asList(entity(9, 1, 10, 0, 0, 0f, 0f), entity(10, 1, 20, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerSnapshot cur = new TrackerSnapshot(2L, "arena",
                Collections.singletonList(entity(9, 1, 10, 0, 0, 0f, 0f, true)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        Map<Integer, Set<Integer>> prevVisible = new HashMap<Integer, Set<Integer>>();
        prevVisible.put(100, new HashSet<Integer>(Arrays.asList(9, 10)));
        TrackerVisibilityPlanner.Plan plan = p.plan(cur, prev, prevVisible);
        List<Integer> removals = plan.remove().get(100);
        assertTrue(removals.contains(9));
        assertTrue(removals.contains(10));
        assertEquals(2, removals.size());
    }

    @Test
    public void stillVisibleEntitiesAreNotReAdded() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot prev = new TrackerSnapshot(1L, "arena",
                Collections.singletonList(entity(11, 1, 10, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerSnapshot cur = new TrackerSnapshot(2L, "arena",
                Collections.singletonList(entity(11, 1, 10, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        Map<Integer, Set<Integer>> prevVisible = new HashMap<Integer, Set<Integer>>();
        prevVisible.put(100, new HashSet<Integer>(Collections.singletonList(11)));
        TrackerVisibilityPlanner.Plan plan = p.plan(cur, prev, prevVisible);
        assertTrue(plan.add().get(100).isEmpty());
        assertTrue(plan.remove().get(100).isEmpty());
    }

    @Test
    public void entityRangeLimitsVisibilityBelowViewDistance() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot snap = new TrackerSnapshot(1L, "arena",
                Collections.singletonList(new TrackerSnapshot.EntityView(1, 1, 40, 0, 0, 0f, 0f, false, 32, false)),
                Collections.singletonList(viewer(100, 0, 0, 0, 64)));
        TrackerVisibilityPlanner.Plan plan = p.plan(snap, null, null);
        assertTrue(plan.add().get(100).isEmpty());
    }

    @Test
    public void lowLatencyFlagBypassesCullingWithoutTypeId() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot snap = new TrackerSnapshot(1L, "arena",
                Collections.singletonList(new TrackerSnapshot.EntityView(5, 0, 500, 0, 0, 0f, 0f, false, 32, true)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan plan = p.plan(snap, null, null);
        assertEquals(Collections.singletonList(5), plan.add().get(100));
        assertEquals(Collections.singletonList(5), plan.lowLatency());
    }

    @Test
    public void nullPreviousVisibleDerivesRemovalsFromPreviousSnapshot() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot prev = new TrackerSnapshot(1L, "arena",
                Collections.singletonList(entity(9, 1, 10, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerSnapshot cur = new TrackerSnapshot(2L, "arena",
                Collections.singletonList(entity(9, 1, 100, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan plan = p.plan(cur, prev, null);
        assertEquals(Collections.singletonList(9), plan.remove().get(100));
        assertTrue(plan.add().get(100).isEmpty());
    }

    @Test
    public void nullPreviousVisibleDerivesAddsFromPreviousSnapshot() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot prev = new TrackerSnapshot(1L, "arena",
                Collections.singletonList(entity(9, 1, 100, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerSnapshot cur = new TrackerSnapshot(2L, "arena",
                Collections.singletonList(entity(9, 1, 10, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan plan = p.plan(cur, prev, null);
        assertEquals(Collections.singletonList(9), plan.add().get(100));
        assertTrue(plan.remove().get(100).isEmpty());
    }

    @Test
    public void firstTickAddsAllVisibleWithoutMovementNoise() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot cur = new TrackerSnapshot(1L, "arena",
                Arrays.asList(entity(1, 1, 10, 0, 0, 0f, 0f), entity(2, 1, 20, 0, 0, 15f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan plan = p.plan(cur, null, null);
        assertEquals(Arrays.asList(1, 2), plan.add().get(100));
        assertTrue(plan.moved().isEmpty());
        assertTrue(plan.rotatedOnly().isEmpty());
    }
}

