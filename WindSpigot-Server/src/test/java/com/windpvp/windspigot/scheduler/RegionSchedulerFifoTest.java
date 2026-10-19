
package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class RegionSchedulerFifoTest {

    @Test
    public void dedicatedRegionExecutesStrictlyFifo() throws Exception {
        RegionScheduler s = new RegionScheduler();
        OwnedRegion region = OwnedRegion.dedicated("bot-1", RegionKind.BOT, 256);
        s.register(region);
        final List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
        for (int i = 0; i < 100; i++) {
            final int v = i;
            s.schedule(region, new Runnable() {
                @Override
                public void run() {
                    order.add(v);
                }
            });
        }
        s.shutdownDrain(5000);
        assertEquals(100, order.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(Integer.valueOf(i), order.get(i));
        }
        assertEquals(100, s.diagnostics().statsFor("bot-1").executed());
    }

    @Test
    public void pumpedRegionExecutesStrictlyFifoOnPumpThread() {
        RegionScheduler s = new RegionScheduler();
        OwnedRegion arena = OwnedRegion.arenaPrimary("arena", 256);
        s.register(arena);
        final List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < 50; i++) {
            final int v = i;
            s.schedule(arena, new Runnable() {
                @Override
                public void run() {
                    order.add(v);
                }
            });
        }
        assertEquals(0, order.size());
        s.pumpOnce(arena);
        assertEquals(50, order.size());
        for (int i = 0; i < 50; i++) {
            assertEquals(Integer.valueOf(i), order.get(i));
        }
        // owner is now the pumping (primary test) thread
        s.assertRegionThread(arena);
    }

    @Test
    public void sendImmutableDeliversInFifoOnOwnerThread() throws Exception {
        RegionScheduler s = new RegionScheduler();
        OwnedRegion a = OwnedRegion.dedicated("npc-a", RegionKind.NPC, 64);
        OwnedRegion b = OwnedRegion.arenaPrimary("arena", 64);
        s.register(a);
        s.register(b);
        final List<Integer> got = new ArrayList<Integer>();
        for (int i = 0; i < 10; i++) {
            final EntityOwnerMessage message = new EntityOwnerMessage(
                    EntityOwnerMessage.Kind.MOVED, i, 1, i, 64, 0);
            s.sendImmutable(b, "npc-a", message, new java.util.function.Consumer<EntityOwnerMessage>() {
                @Override
                public void accept(EntityOwnerMessage m) {
                    got.add(m.entityId());
                }
            });
        }
        s.shutdownDrain(5000);
        assertEquals(10, got.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(Integer.valueOf(i), got.get(i));
        }
    }
}

