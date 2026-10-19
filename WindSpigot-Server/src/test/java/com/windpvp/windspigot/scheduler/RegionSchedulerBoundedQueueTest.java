
package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

public class RegionSchedulerBoundedQueueTest {

    @Test
    public void pumpedQueueRejectsWhenFullAndTracksMetrics() {
        RegionScheduler s = new RegionScheduler();
        OwnedRegion arena = OwnedRegion.arenaPrimary("arena", 4);
        s.register(arena);
        final List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < 4; i++) {
            final int v = i;
            s.schedule(arena, new Runnable() {
                @Override
                public void run() {
                    order.add(v);
                }
            });
        }
        try {
            s.schedule(arena, new Runnable() {
                @Override
                public void run() {
                }
            });
            fail("expected RejectedTaskException when queue is full");
        } catch (RejectedTaskException expected) {
            // expected
        }
        assertEquals(1, s.diagnostics().statsFor("arena").rejected());
        assertEquals(4, s.queueDepth(arena));
        s.pumpOnce(arena);
        for (int i = 0; i < 4; i++) {
            assertEquals(Integer.valueOf(i), order.get(i));
        }
        assertEquals(0, s.queueDepth(arena));
        assertEquals(4, s.diagnostics().statsFor("arena").executed());
        s.schedule(arena, new Runnable() {
            @Override
            public void run() {
            }
        });
        assertEquals(1, s.queueDepth(arena));
        s.pumpOnce(arena);
        assertEquals(0, s.queueDepth(arena));
    }

    @Test
    public void dedicatedQueueRejectsWhenWorkerStalled() throws Exception {
        RegionScheduler s = new RegionScheduler();
        OwnedRegion region = OwnedRegion.dedicated("stall", RegionKind.BOT, 2);
        s.register(region);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        s.schedule(region, new Runnable() {
            @Override
            public void run() {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        started.await();
        final CountDownLatch done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            s.schedule(region, new Runnable() {
                @Override
                public void run() {
                    done.countDown();
                }
            });
        }
        assertEquals(2, s.queueDepth(region));
        try {
            s.schedule(region, new Runnable() {
                @Override
                public void run() {
                }
            });
            fail("expected rejection when dedicated queue is full");
        } catch (RejectedTaskException expected) {
            // expected
        }
        assertEquals(1, s.diagnostics().statsFor("stall").rejected());
        release.countDown();
        s.shutdownDrain(5000);
        assertEquals(0, done.getCount());
        assertEquals(3, s.diagnostics().statsFor("stall").executed());
    }
}

