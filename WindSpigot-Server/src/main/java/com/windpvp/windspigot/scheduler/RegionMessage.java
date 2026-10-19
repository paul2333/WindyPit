package com.windpvp.windspigot.scheduler;

/**
 * Envelope for cross-owner communication. The payload must be a deeply immutable
 * {@link ImmutableOwnerMessage}: the envelope is validated structurally by
 * {@link OwnerMessageValidator} at send time. Delivery runs on the receiving region's owner
 * thread, in FIFO order.
 */
public final class RegionMessage<T extends ImmutableOwnerMessage> {

    private final String fromRegionId;
    private final long sequence;
    private final T payload;

    private RegionMessage(String fromRegionId, long sequence, T payload) {
        this.fromRegionId = fromRegionId;
        this.sequence = sequence;
        this.payload = payload;
    }

    public static <T extends ImmutableOwnerMessage> RegionMessage<T> of(String fromRegionId, long sequence, T payload) {
        OwnerMessageValidator.requireImmutable(payload);
        return new RegionMessage<T>(fromRegionId, sequence, payload);
    }

    public String fromRegionId() {
        return fromRegionId;
    }

    public long sequence() {
        return sequence;
    }

    public T payload() {
        return payload;
    }
}
