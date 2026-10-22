
package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

    /**
     * 【2026-10-23 契约修正】low-latency 类型仍受距离剔除，只是**总是重算**。
     *
     * <p>原用例名 {@code lowLatencyTypesBypassDistanceCulling} 断言 500 格外的箭/TNT
     * 对 viewDistance=48 的观察者可见。那正是缺陷本身：飞出视距的投掷物永远进不了
     * removeList，会持续发包直到自然消失（PvP 服上箭/TNT 是高频实体）。</p>
     *
     * <p>正确语义：low-latency 只跳过"变更类别优化"（任何移动都重算），
     * 可见性仍由距离决定。</p>
     */
    @Test
    public void lowLatencyTypesAreStillDistanceCulledButAlwaysRecomputed() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(
                TrackerVisibilityPlanner.defaultLowLatencyTypeIds(), null);

        // 视距内（viewDistance=48 格 → 半径 48；实体在 x=10）：可见，且标 lowLatency
        TrackerSnapshot near = new TrackerSnapshot(1L, "arena",
                Collections.singletonList(entity(3, 50, 10, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan nearPlan = p.plan(near, null, null);
        assertEquals("视距内的 TNT 必须可见",
                Collections.singletonList(3), nearPlan.add().get(100));
        assertEquals("且被标记为 lowLatency",
                Collections.singletonList(3), nearPlan.lowLatency());

        // 视距外：不可见
        TrackerSnapshot far = new TrackerSnapshot(2L, "arena",
                Collections.singletonList(entity(3, 50, 500, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan farPlan = p.plan(far, null, null);
        assertTrue("飞出视距的 TNT 不得被加入可见集合",
                farPlan.add().get(100).isEmpty());
        assertTrue("也不得出现在 lowLatency 投递列表", farPlan.lowLatency().isEmpty());
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
    public void visibilityPlanCapturesVisibleSetForNextTick() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot snap = new TrackerSnapshot(1L, "arena",
                Arrays.asList(entity(7, 1, 5, 0, 0, 0f, 0f), entity(8, 1, 500, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));

        TrackerVisibilityPlanner.Plan plan = p.planVisibility(snap, null);
        assertEquals("the plan must carry the visible set for the next tick",
                Collections.singleton(7), plan.visible().get(100));
        assertTrue("the first plan has no trustworthy previous visibility", plan.resync());

        TrackerSnapshot next = new TrackerSnapshot(2L, "arena",
                Arrays.asList(entity(7, 1, 6, 0, 0, 0f, 0f), entity(8, 1, 500, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan nextPlan = p.planVisibility(next, snap, plan.visible());
        assertTrue("a still-visible entity must not be re-added",
                nextPlan.add().get(100).isEmpty());
        assertTrue("a still-hidden entity must not be removed",
                nextPlan.remove().get(100).isEmpty());
        assertEquals(Collections.singleton(7), nextPlan.visible().get(100));
        assertFalse("a carried previous visible set is not a resync", nextPlan.resync());
        assertTrue("a lost previous visible set forces a resync",
                p.planVisibility(next, snap, null).resync());
    }

    @Test
    public void visibilityOnlyPlanSkipsChangeClassTables() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);
        TrackerSnapshot prev = new TrackerSnapshot(1L, "arena",
                Collections.singletonList(entity(7, 1, 0, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerSnapshot cur = new TrackerSnapshot(2L, "arena",
                Collections.singletonList(entity(7, 1, 5, 0, 0, 0f, 0f)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));

        TrackerVisibilityPlanner.Plan plan = p.planVisibility(cur, prev);
        TrackerVisibilityPlanner.Plan full = p.plan(cur, prev, null);

        assertEquals("add/remove must match the full plan", full.add().get(100), plan.add().get(100));
        assertEquals("add/remove must match the full plan", full.remove().get(100), plan.remove().get(100));
        assertTrue("visibility-only must not build moved", plan.moved().isEmpty());
        assertTrue("visibility-only must not build rotatedOnly", plan.rotatedOnly().isEmpty());
        assertTrue("visibility-only must not build lowLatency", plan.lowLatency().isEmpty());
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

    /**
     * 【2026-10-23 同上】带 lowLatency 标志的实体同样受距离剔除。
     *
     * <p>原用例 {@code lowLatencyFlagBypassesCullingWithoutTypeId} 断言 x=500 处
     * （viewDistance=48）的实体可见 —— 与按类型判定那条是同一个错误。</p>
     */
    @Test
    public void lowLatencyFlagDoesNotOverrideDistanceCulling() {
        TrackerVisibilityPlanner p = new TrackerVisibilityPlanner(null, null);

        // 视距内（viewDistance=48 → 半径 min(48, 32) = 32；实体在 x=10）：可见
        TrackerSnapshot near = new TrackerSnapshot(1L, "arena",
                Collections.singletonList(new TrackerSnapshot.EntityView(5, 0, 10, 0, 0, 0f, 0f, false, 32, true)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan nearPlan = p.plan(near, null, null);
        assertEquals("视距内的 lowLatency 实体必须可见",
                Collections.singletonList(5), nearPlan.add().get(100));

        // 视距外：不可见
        TrackerSnapshot far = new TrackerSnapshot(2L, "arena",
                Collections.singletonList(new TrackerSnapshot.EntityView(5, 0, 500, 0, 0, 0f, 0f, false, 32, true)),
                Collections.singletonList(viewer(100, 0, 0, 0, 48)));
        TrackerVisibilityPlanner.Plan farPlan = p.plan(far, null, null);
        assertTrue("lowLatency 标志不得覆盖距离剔除", farPlan.add().get(100).isEmpty());
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

