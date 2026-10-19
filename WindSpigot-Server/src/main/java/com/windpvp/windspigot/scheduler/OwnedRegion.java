
package com.windpvp.windspigot.scheduler;

import java.util.Objects;

/**
 * A named scheduling domain with exactly one owner execution context. All Entity/World/Inventory
 * mutation for entities bound to this region must run through its owner thread.
 */
public final class OwnedRegion {

    private final String id;
    private final RegionKind kind;
    private final ExecutionMode mode;
    private final int queueCapacity;

    private OwnedRegion(String id, RegionKind kind, ExecutionMode mode, int queueCapacity) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("region id required");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queue capacity must be positive: " + queueCapacity);
        }
        this.id = id;
        this.kind = kind;
        this.mode = mode;
        this.queueCapacity = queueCapacity;
    }

    public static OwnedRegion of(String id, RegionKind kind, ExecutionMode mode, int queueCapacity) {
        return new OwnedRegion(id, kind, mode, queueCapacity);
    }

    /** ThePit shared arena: pumped on the primary thread by default (arena-owner=primary). */
    public static OwnedRegion arenaPrimary(String id, int queueCapacity) {
        return new OwnedRegion(id, RegionKind.ARENA, ExecutionMode.PUMPED_PRIMARY, queueCapacity);
    }

    /** Independently scheduled region with its own single worker thread. */
    public static OwnedRegion dedicated(String id, RegionKind kind, int queueCapacity) {
        return new OwnedRegion(id, kind, ExecutionMode.DEDICATED_THREAD, queueCapacity);
    }

    public String id() {
        return id;
    }

    public RegionKind kind() {
        return kind;
    }

    public ExecutionMode mode() {
        return mode;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OwnedRegion)) {
            return false;
        }
        return id.equals(((OwnedRegion) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "OwnedRegion{" + id + ", kind=" + kind + ", mode=" + mode + ", cap=" + queueCapacity + "}";
    }
}

