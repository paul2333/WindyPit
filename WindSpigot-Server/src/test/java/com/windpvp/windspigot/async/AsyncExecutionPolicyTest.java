package com.windpvp.windspigot.async;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AsyncExecutionPolicyTest {

    @Test
    public void requestedLiveWorldWorkStaysOnOwnerThread() {
        assertFalse(AsyncExecutionPolicy.isEnabled(true, false));
    }

    @Test
    public void asyncWorkRequiresAnImmutableSnapshot() {
        assertFalse(AsyncExecutionPolicy.isEnabled(false, true));
        assertTrue(AsyncExecutionPolicy.isEnabled(true, true));
    }
}
