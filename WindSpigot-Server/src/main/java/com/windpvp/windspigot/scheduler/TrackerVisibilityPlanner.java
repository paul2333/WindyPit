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
 * <p>Low-latency entity types (TNT, arrows, fishing hooks, potions in 1.8.8) bypass distance
 * culling so their visibility is always recomputed for immediate delivery. Entities carrying a
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

        public Plan(long generation, String regionId, Map<Integer, List<Integer>> add, Map<Integer, List<Integer>> remove,
                    List<Integer> moved, List<Integer> rotatedOnly, List<Integer> lowLatency) {
            this.generation = generation;
            this.regionId = regionId;
            this.add = Collections.unmodifiableMap(add);
            this.remove = Collections.unmodifiableMap(remove);
            this.moved = Collections.unmodifiableList(moved);
            this.rotatedOnly = Collections.unmodifiableList(rotatedOnly);
            this.lowLatency = Collections.unmodifiableList(lowLatency);
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
        if (current == null) {
            throw new IllegalArgumentException("current snapshot required");
        }
        final Map<Integer, List<Integer>> add = new HashMap<Integer, List<Integer>>();
        final Map<Integer, List<Integer>> remove = new HashMap<Integer, List<Integer>>();
        final List<Integer> moved = new ArrayList<Integer>();
        final List<Integer> rotatedOnly = new ArrayList<Integer>();
        final List<Integer> lowLatency = new ArrayList<Integer>();

        final Map<Integer, TrackerSnapshot.EntityView> previousEntities = new HashMap<Integer, TrackerSnapshot.EntityView>();
        final Map<Integer, TrackerSnapshot.ViewerView> previousViewers = new HashMap<Integer, TrackerSnapshot.ViewerView>();
        if (previous != null) {
            for (TrackerSnapshot.EntityView e : previous.entities()) {
                previousEntities.put(e.entityId(), e);
            }
            for (TrackerSnapshot.ViewerView v : previous.viewers()) {
                previousViewers.put(v.entityId(), v);
            }
        }
        final Set<Integer> currentEntityIds = new HashSet<Integer>();
        for (TrackerSnapshot.EntityView e : current.entities()) {
            currentEntityIds.add(e.entityId());
        }

        for (TrackerSnapshot.ViewerView viewer : current.viewers()) {
            final Set<Integer> wasVisible;
            if (previousVisible != null) {
                wasVisible = previousVisible.containsKey(viewer.entityId())
                        ? previousVisible.get(viewer.entityId())
                        : Collections.<Integer>emptySet();
            } else if (previous != null) {
                wasVisible = derivePreviousVisibility(previous, previousEntities, previousViewers, viewer.entityId());
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
                boolean visible;
                if (lowLatencyType) {
                    visible = true;
                } else {
                    final double dx = e.x() - viewer.x();
                    final double dy = e.y() - viewer.y();
                    final double dz = e.z() - viewer.z();
                    final double dist2 = dx * dx + dy * dy + dz * dz;
                    final Double multiplier = typeDistanceMultiplier.get(e.typeId());
                    final double radius = effectiveRadius(viewer, e, multiplier);
                    visible = dist2 <= radius * radius;
                }
                if (visible) {
                    nowVisible.add(e.entityId());
                    if (lowLatencyType && !lowLatency.contains(e.entityId())) {
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
        }

        if (previous != null) {
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

        return new Plan(current.generation(), current.regionId(), add, remove, moved, rotatedOnly, lowLatency);
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
                                                  Map<Integer, TrackerSnapshot.EntityView> previousEntities,
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
            if (e.lowLatency() || lowLatencyTypeIds.contains(e.typeId())) {
                visible.add(e.entityId());
                continue;
            }
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
