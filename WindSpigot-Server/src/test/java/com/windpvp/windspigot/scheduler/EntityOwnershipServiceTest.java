
package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class EntityOwnershipServiceTest {

    private RegionScheduler scheduler;
    private OwnedRegion arena;

    private void setupArena() {
        scheduler = new RegionScheduler();
        arena = OwnedRegion.arenaPrimary("arena", 64);
        scheduler.register(arena);
    }

    @Test
    public void destroyRetiresPendingWork() {
        setupArena();
        RegionScheduler s = scheduler;
        EntityOwnershipService o = new EntityOwnershipService(s, new EntityScheduler(s, s.diagnostics()));
        o.onEntityCreated(1, arena);
        final List<Integer> runs = new ArrayList<Integer>();
        o.scheduleWork(1, new Runnable() {
            @Override
            public void run() {
                runs.add(1);
            }
        });
        o.scheduleWork(1, new Runnable() {
            @Override
            public void run() {
                runs.add(2);
            }
        });
        assertEquals(2, o.onEntityDestroyed(1));
        assertNull(o.regionOf(1));
        s.pumpOnce(arena);
        assertEquals(0, runs.size());
        assertEquals(2, s.diagnostics().statsFor("arena").retired());
    }

    @Test
    public void migrateRetiresOldWorkAndRebinds() throws Exception {
        RegionScheduler s = new RegionScheduler();
        OwnedRegion arena = OwnedRegion.arenaPrimary("arena", 64);
        OwnedRegion npc = OwnedRegion.dedicated("npc-1", RegionKind.NPC, 64);
        s.register(arena);
        s.register(npc);
        EntityOwnershipService o = new EntityOwnershipService(s, new EntityScheduler(s, s.diagnostics()));
        o.onEntityCreated(5, arena);
        final List<String> runs = new ArrayList<String>();
        o.scheduleWork(5, new Runnable() {
            @Override
            public void run() {
                runs.add("old");
            }
        });
        assertEquals(1, o.onEntityMigrated(5, npc));
        assertEquals("npc-1", o.regionOf(5).id());
        o.scheduleWork(5, new Runnable() {
            @Override
            public void run() {
                runs.add("new:" + Thread.currentThread().getName());
            }
        });
        s.shutdownDrain(5000);
        assertEquals(1, runs.size());
        assertEquals("new:WindyPit-Region-npc-1", runs.get(0));
        assertEquals(0, o.entitiesInRegion(arena).size());
        assertEquals(1, o.entitiesInRegion(npc).size());
    }

    @Test
    public void worldUnloadDestroysOnlyBoundEntities() {
        RegionScheduler s = new RegionScheduler();
        OwnedRegion arena = OwnedRegion.arenaPrimary("arena", 64);
        OwnedRegion other = OwnedRegion.arenaPrimary("other", 64);
        s.register(arena);
        s.register(other);
        EntityOwnershipService o = new EntityOwnershipService(s, new EntityScheduler(s, s.diagnostics()));
        o.onEntityCreated(1, arena);
        o.onEntityCreated(2, arena);
        o.onEntityCreated(3, other);
        assertEquals(2, o.onWorldUnload(Arrays.asList(arena)));
        assertEquals(0, o.entitiesInRegion(arena).size());
        assertEquals(1, o.entitiesInRegion(other).size());
        // entity 3 still works
        final List<Integer> runs = new ArrayList<Integer>();
        o.scheduleWork(3, new Runnable() {
            @Override
            public void run() {
                runs.add(3);
            }
        });
        s.pumpOnce(other);
        assertEquals(1, runs.size());
    }

    @Test
    public void doubleDestroyIsIdempotentAndPlayerQuitFollowsDestroy() {
        setupArena();
        RegionScheduler s = scheduler;
        EntityOwnershipService o = new EntityOwnershipService(s, new EntityScheduler(s, s.diagnostics()));
        o.onEntityCreated(9, arena);
        assertEquals(0, o.onEntityDestroyed(9));
        assertEquals(0, o.onPlayerQuit(9));
    }

    @Test
    public void scheduleAfterDestroyThrows() {
        setupArena();
        RegionScheduler s = scheduler;
        EntityOwnershipService o = new EntityOwnershipService(s, new EntityScheduler(s, s.diagnostics()));
        o.onEntityCreated(4, arena);
        o.onEntityDestroyed(4);
        try {
            o.scheduleWork(4, new Runnable() {
                @Override
                public void run() {
                }
            });
            fail("expected IllegalStateException for destroyed entity");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void checkOwnerRecordsViolationOnWrongThread() throws Exception {
        RegionScheduler s = new RegionScheduler();
        OwnedRegion dedicated = OwnedRegion.dedicated("guard", RegionKind.NPC, 16);
        s.register(dedicated);
        EntityOwnershipService o = new EntityOwnershipService(s, new EntityScheduler(s, s.diagnostics()));
        o.onEntityCreated(2, dedicated);
        try {
            o.checkOwner(2);
            fail("expected violation on non-owner thread");
        } catch (RegionScheduler.RegionThreadViolationException expected) {
            // expected
        }
        assertEquals(1, s.violations().totalViolations());
        s.shutdownDrain(5000);
        assertTrue(s.isClosed());
    }
}

