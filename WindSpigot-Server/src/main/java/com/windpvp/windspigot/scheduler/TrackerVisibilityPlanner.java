package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure visibility planning for the async tracker (W2-TRACKER). Given the current immutable
 * snapshot, the previous snapshot and (optionally) the previously visible pairs, produces the
 * per-viewer add/remove plan plus entity change classes (moved / rotated-only / low-latency).
 *
 * <p>When {@code previousVisible} is null the planner derives the previous visibility itself from
 * the previous snapshot, so the production owner-domain path never has to copy a large
 * viewer-&gt;entities map between threads. The derivation is exact: visibility is a pure function
 * of the two positions and the effective radius.</p>
 *
 * <p>Low-latency entity types (TNT, arrows, fishing hooks, potions in 1.8.8) <b>bypass the
 * change-class optimisation</b> so their visibility is always recomputed for immediate delivery.
 * They are still distance-culled like everything else: "needs to be recomputed every tick" is not
 * the same thing as "visible to everyone". (2026-10-23: the earlier implementation conflated the
 * two — low-latency entities were forced visible, so an arrow that flew out of view kept being
 * tracked and kept sending packets until it despawned.) Entities carrying a
 * non-zero {@link TrackerSnapshot.EntityView#rangeBlocks()} are culled by the smaller of the
 * viewer's view distance and the entity's own tracking range, matching the vanilla tracker.</p>
 */
public final class TrackerVisibilityPlanner {

    /** 1.8.8 entity type ids with low-latency tracking requirements. */
    public static Set<Integer> defaultLowLatencyTypeIds() {
        Set<Integer> ids = new HashSet<Integer>();
        ids.add(50); // TNTPrimed
        ids.add(60); // Arrow
        ids.add(90); // FishingHook
        ids.add(73); // EntityPotion (splash potion)
        return ids;
    }

    public static final class Plan {
        private final long generation;
        private final String regionId;
        private final Map<Integer, List<Integer>> add;
        private final Map<Integer, List<Integer>> remove;
        private final List<Integer> moved;
        private final List<Integer> rotatedOnly;
        private final List<Integer> lowLatency;
        private final Map<Integer, Set<Integer>> visible;
        private final boolean resync;

        public Plan(long generation, String regionId, Map<Integer, List<Integer>> add, Map<Integer, List<Integer>> remove,
                    List<Integer> moved, List<Integer> rotatedOnly, List<Integer> lowLatency) {
            this(generation, regionId, add, remove, moved, rotatedOnly, lowLatency, null, false);
        }

        public Plan(long generation, String regionId, Map<Integer, List<Integer>> add, Map<Integer, List<Integer>> remove,
                    List<Integer> moved, List<Integer> rotatedOnly, List<Integer> lowLatency,
                    Map<Integer, Set<Integer>> visible) {
            this(generation, regionId, add, remove, moved, rotatedOnly, lowLatency, visible, false);
        }

        public Plan(long generation, String regionId, Map<Integer, List<Integer>> add, Map<Integer, List<Integer>> remove,
                    List<Integer> moved, List<Integer> rotatedOnly, List<Integer> lowLatency,
                    Map<Integer, Set<Integer>> visible, boolean resync) {
            this.generation = generation;
            this.regionId = regionId;
            this.add = Collections.unmodifiableMap(add);
            this.remove = Collections.unmodifiableMap(remove);
            this.moved = Collections.unmodifiableList(moved);
            this.rotatedOnly = Collections.unmodifiableList(rotatedOnly);
            this.lowLatency = Collections.unmodifiableList(lowLatency);
            this.visible = visible == null ? null : Collections.unmodifiableMap(visible);
            this.resync = resync;
        }

        public long generation() {
            return generation;
        }

        public String regionId() {
            return regionId;
        }

        public Map<Integer, List<Integer>> add() {
            return add;
        }

        public Map<Integer, List<Integer>> remove() {
            return remove;
        }

        public List<Integer> moved() {
            return moved;
        }

        public List<Integer> rotatedOnly() {
            return rotatedOnly;
        }

        public List<Integer> lowLatency() {
            return lowLatency;
        }

        /**
         * viewerId -&gt; entity ids visible after this plan. Null for plans that did not request
         * capture (the legacy/test path). The production owner-domain path feeds this map back as
         * {@code previousVisible}, so the next tick does not re-derive it from the previous
         * snapshot.
         */
        public Map<Integer, Set<Integer>> visible() {
            return visible;
        }

        /**
         * True when this plan started from no trustworthy previous visibility (first tick, plan
         * loss, empty world). The applier uses it to reconcile stale tracked pairs that the
         * add/remove delta cannot see.
         */
        public boolean resync() {
            return resync;
        }
    }

    private final Set<Integer> lowLatencyTypeIds;
    private final Map<Integer, Double> typeDistanceMultiplier;

    public TrackerVisibilityPlanner(Set<Integer> lowLatencyTypeIds, Map<Integer, Double> typeDistanceMultiplier) {
        this.lowLatencyTypeIds = lowLatencyTypeIds == null
                ? Collections.<Integer>emptySet()
                : Collections.unmodifiableSet(new HashSet<Integer>(lowLatencyTypeIds));
        this.typeDistanceMultiplier = typeDistanceMultiplier == null
                ? Collections.<Integer, Double>emptyMap()
                : Collections.unmodifiableMap(new HashMap<Integer, Double>(typeDistanceMultiplier));
    }

    /**
     * @param current          the snapshot being planned
     * @param previous         the previous snapshot (null on first planning)
     * @param previousVisible  viewerId -&gt; set of entityIds visible after the previous plan; when
     *                         null the planner derives it from {@code previous}
     */
    public Plan plan(TrackerSnapshot current, TrackerSnapshot previous, Map<Integer, Set<Integer>> previousVisible) {
        return planInternal(current, previous, previousVisible, true, false);
    }

    /**
     * Visibility-only variant used by the production owner-domain path. The applier consumes only
     * add/remove; the moved/rotated/low-latency classifications are test/legacy consumers, so
     * building their per-tick O(N) map would be work with no reader.
     */
    public Plan planVisibility(TrackerSnapshot current, TrackerSnapshot previous) {
        return planVisibility(current, previous, null);
    }

    /**
     * Visibility-only variant that also captures the current per-viewer visible set. The owner
     * domain feeds that map back as {@code previousVisible} on the next tick, which removes the
     * second O(V*E) pass ({@link #derivePreviousVisibility}) from the steady-state path.
     */
    public Plan planVisibility(TrackerSnapshot current, TrackerSnapshot previous,
            Map<Integer, Set<Integer>> previousVisible) {
        return planInternal(current, previous, previousVisible, false, true);
    }

    private Plan planInternal(TrackerSnapshot current, TrackerSnapshot previous,
            Map<Integer, Set<Integer>> previousVisible, boolean changeClasses, boolean captureVisible) {
        if (current == null) {
            throw new IllegalArgumentException("current snapshot required");
        }
        final Map<Integer, List<Integer>> add = new HashMap<Integer, List<Integer>>();
        final Map<Integer, List<Integer>> remove = new HashMap<Integer, List<Integer>>();
        final List<Integer> moved = changeClasses
                ? new ArrayList<Integer>() : Collections.<Integer>emptyList();
        final List<Integer> rotatedOnly = changeClasses
                ? new ArrayList<Integer>() : Collections.<Integer>emptyList();
        final List<Integer> lowLatency = changeClasses
                ? new ArrayList<Integer>() : Collections.<Integer>emptyList();

        final Map<Integer, TrackerSnapshot.EntityView> previousEntities = changeClasses
                ? new HashMap<Integer, TrackerSnapshot.EntityView>() : null;
        final Map<Integer, TrackerSnapshot.ViewerView> previousViewers =
                previous != null && previousVisible == null
                        ? new HashMap<Integer, TrackerSnapshot.ViewerView>() : null;
        if (previous != null) {
            if (previousEntities != null) {
                for (TrackerSnapshot.EntityView e : previous.entities()) {
                    previousEntities.put(e.entityId(), e);
                }
            }
            if (previousViewers != null) {
                for (TrackerSnapshot.ViewerView v : previous.viewers()) {
                    previousViewers.put(v.entityId(), v);
                }
            }
        }
        final Set<Integer> currentEntityIds = new HashSet<Integer>();
        for (TrackerSnapshot.EntityView e : current.entities()) {
            currentEntityIds.add(e.entityId());
        }
        final Map<Integer, Set<Integer>> visibleByViewer = captureVisible
                ? new HashMap<Integer, Set<Integer>>() : null;

        for (TrackerSnapshot.ViewerView viewer : current.viewers()) {
            final Set<Integer> wasVisible;
            if (previousVisible != null) {
                wasVisible = previousVisible.containsKey(viewer.entityId())
                        ? previousVisible.get(viewer.entityId())
                        : Collections.<Integer>emptySet();
            } else if (previous != null) {
                wasVisible = derivePreviousVisibility(previous, previousViewers, viewer.entityId());
            } else {
                wasVisible = Collections.<Integer>emptySet();
            }
            final List<Integer> addList = new ArrayList<Integer>();
            final List<Integer> removeList = new ArrayList<Integer>();
            final Set<Integer> nowVisible = new HashSet<Integer>();

            for (TrackerSnapshot.EntityView e : current.entities()) {
                if (e.entityId() == viewer.entityId()) {
                    continue;
                }
                if (e.dead()) {
                    if (wasVisible.contains(e.entityId())) {
                        removeList.add(e.entityId());
                    }
                    continue;
                }
                final boolean lowLatencyType = e.lowLatency() || lowLatencyTypeIds.contains(e.typeId());
                // 【2026-10-23 修复：lowLatency 不再意味着"无条件可见"】
                //
                // 原来这一支是 `if (lowLatencyType) visible = true;` —— 箭、TNT、
                // 鱼钩、药水（typeId 60/50/90/…）因此**完全绕过距离判定**。
                // 而移除只有两条路：实体死亡（上面的 e.dead() 分支），
                // 或 applyRemoves 收到 removeList。既然它们永远不会被判不可见，
                // 一支飞出视距的箭就会**一直留在观察者的 tracked 集合里持续发包**，
                // 直到它自然消失。PvP 服上箭/TNT 是高频实体，这是纯带宽与客户端实体表浪费。
                //
                // lowLatency 的语义是"这个实体变化快、需要尽快重算可见性"
                // （影响的是变更类别判定，见下方 lowLatency 收集），
                // **不是**"它对所有人都可见"。可见性必须始终由距离决定。
                final double dx = e.x() - viewer.x();
                final double dy = e.y() - viewer.y();
                final double dz = e.z() - viewer.z();
                final double dist2 = dx * dx + dy * dy + dz * dz;
                final Double multiplier = typeDistanceMultiplier.get(e.typeId());
                final double radius = effectiveRadius(viewer, e, multiplier);
                final boolean visible = dist2 <= radius * radius;
                if (visible) {
                    nowVisible.add(e.entityId());
                    if (changeClasses && lowLatencyType && !lowLatency.contains(e.entityId())) {
                        lowLatency.add(e.entityId());
                    }
                    if (!wasVisible.contains(e.entityId())) {
                        addList.add(e.entityId());
                    }
                } else if (wasVisible.contains(e.entityId())) {
                    removeList.add(e.entityId());
                }
            }

            // entities that disappeared from the snapshot entirely
            for (Integer was : wasVisible) {
                if (!nowVisible.contains(was) && !removeList.contains(was) && !currentEntityIds.contains(was)) {
                    removeList.add(was);
                }
            }

            add.put(viewer.entityId(), addList);
            remove.put(viewer.entityId(), removeList);
            if (visibleByViewer != null) {
                visibleByViewer.put(viewer.entityId(), nowVisible);
            }
        }

        if (changeClasses && previous != null) {
            for (TrackerSnapshot.EntityView e : current.entities()) {
                final TrackerSnapshot.EntityView prev = previousEntities.get(e.entityId());
                if (prev == null || e.dead()) {
                    continue;
                }
                final double dx = e.x() - prev.x();
                final double dy = e.y() - prev.y();
                final double dz = e.z() - prev.z();
                final boolean positionChanged = (dx * dx + dy * dy + dz * dz) > 1.0e-8D;
                if (positionChanged) {
                    moved.add(e.entityId());
                } else if (Math.abs(e.yaw() - prev.yaw()) > 1.0e-4F || Math.abs(e.pitch() - prev.pitch()) > 1.0e-4F) {
                    rotatedOnly.add(e.entityId());
                }
            }
        }

        final boolean resync = previous == null || previousVisible == null;
        return new Plan(current.generation(), current.regionId(), add, remove, moved, rotatedOnly,
                lowLatency, visibleByViewer, resync);
    }

    private double effectiveRadius(TrackerSnapshot.ViewerView viewer, TrackerSnapshot.EntityView entity, Double multiplier) {
        int radius = viewer.viewDistanceBlocks();
        if (entity.rangeBlocks() > 0) {
            radius = Math.min(radius, entity.rangeBlocks());
        }
        return radius * (multiplier == null ? 1.0D : multiplier.doubleValue());
    }

    /**
     * Recomputes the previous viewer's visible set from the previous snapshot. Visibility is a
     * pure distance function, so this is exact for the vanilla tracker's range rules.
     */
    private Set<Integer> derivePreviousVisibility(TrackerSnapshot previous,
                                                  Map<Integer, TrackerSnapshot.ViewerView> previousViewers,
                                                  int viewerId) {
        final TrackerSnapshot.ViewerView viewer = previousViewers.get(viewerId);
        if (viewer == null) {
            return Collections.emptySet();
        }
        final Set<Integer> visible = new HashSet<Integer>();
        for (TrackerSnapshot.EntityView e : previous.entities()) {
            if (e.entityId() == viewerId || e.dead()) {
                continue;
            }
            // 【2026-10-23 与主循环保持一致】lowLatency 不再是"无条件可见"，
            // 这里也必须按距离判定，否则"上一帧可见集合"会系统性地多算实体，
            // 让 add/remove 的差集永远收敛不到正确状态。
            final double dx = e.x() - viewer.x();
            final double dy = e.y() - viewer.y();
            final double dz = e.z() - viewer.z();
            final double dist2 = dx * dx + dy * dy + dz * dz;
            final Double multiplier = typeDistanceMultiplier.get(e.typeId());
            final double radius = effectiveRadius(viewer, e, multiplier);
            if (dist2 <= radius * radius) {
                visible.add(e.entityId());
            }
        }
        return visible;
    }
}
