package com.windpvp.windspigot.async;

/**
 * Keeps optional background gameplay work behind an explicit safety gate.
 *
 * <p>Live NMS world state is mutable and must remain on the owner thread. A feature may only run
 * off-thread when it reads a value snapshot instead of live objects, or when it re-validates its
 * inputs before publishing a result.
 *
 * <p>Path searching qualifies: {@link net.minecraft.server.ChunkCache} exposes
 * {@code captureGeneration()} / {@code matchesGeneration(int[])}, and the worker discards a path
 * whose chunk generations moved while it was computing. Explosion density does not qualify yet: it
 * ray-traces live world state and publishes into a shared density cache with no re-validation.
 */
public final class AsyncExecutionPolicy {

    private AsyncExecutionPolicy() {
    }

    /**
     * @param requested         the value from windspigot.yml
     * @param safeForConcurrency true when the caller has a re-validated read path
     */
    public static boolean isEnabled(boolean requested, boolean safeForConcurrency) {
        return requested && safeForConcurrency;
    }
}
