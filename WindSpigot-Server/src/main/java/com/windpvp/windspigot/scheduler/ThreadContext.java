
package com.windpvp.windspigot.scheduler;

/**
 * Describes the execution context a piece of code is running in. Used by owner assertions and
 * thread-violation diagnostics; later waves (tick pipeline, tracker, compute) attach it to work
 * submission so stale or misplaced work is detectable.
 */
public final class ThreadContext {

    public enum Kind {
        GLOBAL_REGION,
        REGION,
        ENTITY,
        COMPUTE,
        PERSISTENCE,
        NETWORK
    }

    private final Kind kind;
    private final String regionId;
    private final int entityId;

    private ThreadContext(Kind kind, String regionId, int entityId) {
        this.kind = kind;
        this.regionId = regionId;
        this.entityId = entityId;
    }

    public static ThreadContext globalRegion() {
        return new ThreadContext(Kind.GLOBAL_REGION, "global", -1);
    }

    public static ThreadContext region(String regionId) {
        return new ThreadContext(Kind.REGION, regionId, -1);
    }

    public static ThreadContext entity(String regionId, int entityId) {
        return new ThreadContext(Kind.ENTITY, regionId, entityId);
    }

    public Kind kind() {
        return kind;
    }

    public String regionId() {
        return regionId;
    }

    public int entityId() {
        return entityId;
    }

    public String describe() {
        return kind + "{" + regionId + (entityId >= 0 ? "," + entityId : "") + "}";
    }
}

