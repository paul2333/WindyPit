package com.windpvp.windspigot.scheduler;

/**
 * Marker for payloads that are allowed to cross an owner boundary (W1-REGION-REAL / Prompt 70).
 *
 * <p>The marker alone is not trusted: {@link OwnerMessageValidator} additionally requires the
 * concrete class to be {@code final}, every instance field to be {@code final}, and every field
 * type to be a primitive, {@link String} or enum. A live NMS entity/world reference, a collection,
 * or a mutable DTO therefore cannot be sent through {@link RegionScheduler#sendImmutable}.</p>
 */
public interface ImmutableOwnerMessage {

    /** Stable message type used by diagnostics; must not be derived from mutable state. */
    String messageType();
}
