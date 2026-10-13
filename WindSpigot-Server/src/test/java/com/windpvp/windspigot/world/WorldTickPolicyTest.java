package com.windpvp.windspigot.world;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorldTickPolicyTest {

    @Test
    public void skipsEntityTrackingWhenWorldHasNoPlayers() {
        assertFalse(WorldTickPolicy.shouldUpdateEntityTracking(0));
    }

    @Test
    public void updatesEntityTrackingWhenWorldHasPlayers() {
        assertTrue(WorldTickPolicy.shouldUpdateEntityTracking(1));
    }
}
