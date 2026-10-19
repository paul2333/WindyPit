package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable tracker snapshot (W2-TRACKER): per-region entity and viewer state at one generation.
 * Built on the owner thread, consumed by async compute; never mutated after construction.
 */
public final class TrackerSnapshot {

    public static final class EntityView {
        private final int entityId;
        private final int typeId;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final boolean dead;
        private final int rangeBlocks;
        private final boolean lowLatency;

        public EntityView(int entityId, int typeId, double x, double y, double z, float yaw, float pitch, boolean dead) {
            this(entityId, typeId, x, y, z, yaw, pitch, dead, 0, false);
        }

        /**
         * @param rangeBlocks the entity's own tracking range in blocks, or 0 to fall back to the
         *                    viewer's view distance (legacy behaviour)
         * @param lowLatency  true for projectile/TNT entities whose visibility bypasses culling
         */
        public EntityView(int entityId, int typeId, double x, double y, double z, float yaw, float pitch,
                          boolean dead, int rangeBlocks, boolean lowLatency) {
            this.entityId = entityId;
            this.typeId = typeId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.dead = dead;
            this.rangeBlocks = rangeBlocks;
            this.lowLatency = lowLatency;
        }

        public int entityId() {
            return entityId;
        }

        public int typeId() {
            return typeId;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public float yaw() {
            return yaw;
        }

        public float pitch() {
            return pitch;
        }

        public boolean dead() {
            return dead;
        }

        public int rangeBlocks() {
            return rangeBlocks;
        }

        public boolean lowLatency() {
            return lowLatency;
        }
    }

    public static final class ViewerView {
        private final int entityId;
        private final double x;
        private final double y;
        private final double z;
        private final int viewDistanceBlocks;

        public ViewerView(int entityId, double x, double y, double z, int viewDistanceBlocks) {
            this.entityId = entityId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.viewDistanceBlocks = viewDistanceBlocks;
        }

        public int entityId() {
            return entityId;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public int viewDistanceBlocks() {
            return viewDistanceBlocks;
        }
    }

    private final long generation;
    private final String regionId;
    private final List<EntityView> entities;
    private final List<ViewerView> viewers;

    public TrackerSnapshot(long generation, String regionId, List<EntityView> entities, List<ViewerView> viewers) {
        this.generation = generation;
        this.regionId = regionId;
        this.entities = Collections.unmodifiableList(new ArrayList<EntityView>(entities));
        this.viewers = Collections.unmodifiableList(new ArrayList<ViewerView>(viewers));
    }

    public long generation() {
        return generation;
    }

    public String regionId() {
        return regionId;
    }

    public List<EntityView> entities() {
        return entities;
    }

    public List<ViewerView> viewers() {
        return viewers;
    }
}
