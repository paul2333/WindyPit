package com.windpvp.windspigot.world;

/** Small allocation-free policies used by the synchronous world owner. */
public final class WorldTickPolicy {

    private WorldTickPolicy() {
    }

    public static boolean shouldUpdateEntityTracking(int worldPlayerCount) {
        return worldPlayerCount > 0;
    }
}
