
package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class RegionSchedulerShutdownDrainTest {

    @Test
    public void pumpedShutdownDrainsExactlyOnceAndRejectsNewWork() throws Exception {
        RegionScheduler s = new RegionScheduler();
        OwnedRegion arena = OwnedRegion.arenaPrimary("arena", 16);
        s.register(arena);
        final List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < 3; i++) {
            final int v = i;
            s.schedule(arena, new Runnable() {
                @Override
                public void run() {
                    order.add(v);
                }
            });
        }
        s.shutdownDrain(5000);
        assertEquals(3, order.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(Integer.valueOf(i), order.get(i));
        }
        assertTrue(s.isClosed());
        try {
            s.schedule(arena, new Runnable() {
                @Override
                public void run() {
                }
            });
            fail("expected rejection after close");
        } catch (RejectedTaskException expected) {
            // expected
        }
        // idempotent: no double execution
        s.shutdownDrain(5000);
        assertEquals(3, order.size());
        assertEquals(3, s.diagnostics().statsFor("arena").executed());
    }

    @Test
    public void dedicatedShutdownRunsQueuedTasksThenTerminatesWorker() throws Exception {
        RegionScheduler s = new RegionScheduler();
        OwnedRegion region = OwnedRegion.dedicated("drain", RegionKind.DECOR, 32);
        s.register(region);
        final List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < 5; i++) {
            final int v = i;
            s.schedule(region, new Runnable() {
                @Override
                public void run() {
                    order.add(v);
                }
            });
        }
        s.shutdownDrain(5000);
        assertEquals(5, order.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(Integer.valueOf(i), order.get(i));
        }
        try {
            s.schedule(region, new Runnable() {
                @Override
                public void run() {
                }
            });
            fail("expected rejection after close");
        } catch (RejectedTaskException expected) {
            // expected
        }
    }
}

