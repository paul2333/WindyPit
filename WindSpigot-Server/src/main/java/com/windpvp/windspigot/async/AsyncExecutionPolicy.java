package com.windpvp.windspigot.async;

/**
 * Keeps optional background gameplay work behind an explicit snapshot safety
 * check. Live NMS world state is mutable and must remain on the owner thread.
 */
public final class AsyncExecutionPolicy {

    private AsyncExecutionPolicy() {
    }

    public static boolean isEnabled(boolean requested, boolean immutableSnapshot) {
        return requested && immutableSnapshot;
    }
}
