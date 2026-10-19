package com.windpvp.windspigot.scheduler;

/**
 * Immutable entity event that may cross an owner boundary (W1-REGION-REAL / Prompt 70).
 *
 * <p>Only primitive/ID state travels: the receiving owner must resolve its own live entity from
 * the id and its own registry generation. A live {@code net.minecraft.server.Entity} reference is
 * never carried across threads.</p>
 */
public final class EntityOwnerMessage implements ImmutableOwnerMessage {

    public enum Kind {
        MOVED,
        REMOVED,
        TRANSFER
    }

    private final Kind kind;
    private final int entityId;
    private final int worldGeneration;
    private final double x;
    private final double y;
    private final double z;

    public EntityOwnerMessage(Kind kind, int entityId, int worldGeneration, double x, double y, double z) {
        if (kind == null) {
            throw new IllegalArgumentException("message kind required");
        }
        this.kind = kind;
        this.entityId = entityId;
        this.worldGeneration = worldGeneration;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Kind kind() {
        return kind;
    }

    public int entityId() {
        return entityId;
    }

    public int worldGeneration() {
        return worldGeneration;
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

    @Override
    public String messageType() {
        return "entity-" + kind.name();
    }

    @Override
    public String toString() {
        return "EntityOwnerMessage{" + kind + ", entity=" + entityId + ", generation=" + worldGeneration + "}";
    }
}
