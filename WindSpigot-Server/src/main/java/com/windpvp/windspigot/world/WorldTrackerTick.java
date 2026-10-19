
package com.windpvp.windspigot.world;

/**
 * Runs the Spigot "tracker" timing window around the per-tick entity-tracking update of one world.
 *
 * Contract, asserted by WorldTrackerTickTest:
 * - the timing window is opened exactly once and closed exactly once per call;
 * - the timing window is closed through the finally path even when the tracker update throws;
 * - the tracker update runs exactly once per call and only while the world has players;
 * - no branch skips the closing timing call.
 *
 * The caller implements {@link Target}, so the server-thread path allocates nothing per tick.
 */
public final class WorldTrackerTick {

    /** Implemented by WorldTicker; every method runs on the server thread inside one world tick. */
    public interface Target {

        int worldPlayerCount();

        void startTrackerTiming();

        void updateTracker();

        void stopTrackerTiming();
    }

    private WorldTrackerTick() {
    }

    public static void run(Target target) {
        target.startTrackerTiming();
        try {
            if (WorldTickPolicy.shouldUpdateEntityTracking(target.worldPlayerCount())) {
                target.updateTracker();
            }
        } finally {
            target.stopTrackerTiming();
        }
    }
}

