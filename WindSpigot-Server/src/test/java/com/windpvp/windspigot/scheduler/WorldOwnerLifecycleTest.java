package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Owner/generation lifecycle assertions for world, entity, player-quit and cross-world transfer
 * operations (Prompt 70). Every mutation is executed through the real {@link RegionScheduler};
 * the test thread is deliberately not an owner, so wrong-thread calls must fail fast.
 */
public class WorldOwnerLifecycleTest {

    private RegionScheduler scheduler;
    private WorldOwnerRegistry registry;
    private OwnedRegion alphaRegion;
    private OwnedRegion betaRegion;

    @Before
    public void setUp() {
        scheduler = new RegionScheduler();
        alphaRegion = OwnedRegion.dedicated("world-owner-alpha", RegionKind.WORLD, 64);
        betaRegion = OwnedRegion.dedicated("world-owner-beta", RegionKind.WORLD, 64);
        scheduler.register(alphaRegion);
        scheduler.register(betaRegion);
        registry = new WorldOwnerRegistry(scheduler);
    }

    @After
    public void tearDown() throws Exception {
        scheduler.shutdownDrain(5000);
    }

    @Test
    public void worldRegistrationBindsOwnerAndMonotonicGeneration() throws Exception {
        final WorldOwnerRegistry.WorldOwner alpha = registry.registerWorld("alpha", alphaRegion);

        assertEquals("alpha", alpha.worldName());
        assertEquals("world-owner-alpha", alpha.regionId());
        assertEquals(1L, alpha.generation());
        assertEquals(1, registry.worldCount());

        assertNull("owner thread must pass the owner assertion",
                runOn(alphaRegion, new Runnable() {
                    @Override
                    public void run() {
                        registry.assertOwner(alpha);
                    }
                }));
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerEntity(alpha, 101);
            }
        }));
        assertEquals("alpha", registry.entityWorld(101));

        Throwable wrongThread = null;
        try {
            registry.assertOwner(alpha);
        } catch (Throwable t) {
            wrongThread = t;
        }
        assertTrue("non-owner assertion must fail fast",
                wrongThread instanceof WorldOwnerRegistry.OwnerViolationException);
        assertTrue("owner violation must be counted", registry.ownerViolations() >= 1L);
    }

    @Test
    public void unloadInvalidatesGenerationAndReRegisterIsMonotonic() throws Exception {
        final WorldOwnerRegistry.WorldOwner first = registry.registerWorld("alpha", alphaRegion);

        final WorldOwnerRegistry.WorldUnload unload = registry.unregisterWorld("alpha");
        assertEquals(1L, unload.retiredGeneration());
        assertEquals(2L, unload.nextGeneration());
        assertFalse(registry.isWorldActive("alpha"));

        final Throwable stale = runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.assertOwner(first);
            }
        });
        assertTrue("old handle must be rejected after unload",
                stale instanceof WorldOwnerRegistry.StaleOwnerGenerationException);

        final WorldOwnerRegistry.WorldOwner second = registry.registerWorld("alpha", alphaRegion);
        assertEquals("re-register must never reuse a generation", 3L, second.generation());
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.assertOwner(second);
            }
        }));
        final Throwable staleOld = runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.assertOwner(first);
            }
        });
        assertTrue(staleOld instanceof WorldOwnerRegistry.StaleOwnerGenerationException);
        assertTrue(registry.staleRejections() >= 2L);
    }

    @Test
    public void queuedOwnerWorkWithStaleGenerationFailsFast() throws Exception {
        final WorldOwnerRegistry.WorldOwner owner = registry.registerWorld("alpha", alphaRegion);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch checked = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        scheduler.schedule(alphaRegion, new Runnable() {
            @Override
            public void run() {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        assertTrue("blocker must occupy the owner worker", started.await(5, TimeUnit.SECONDS));

        scheduler.schedule(alphaRegion, new Runnable() {
            @Override
            public void run() {
                try {
                    registry.assertOwner(owner);
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    checked.countDown();
                }
            }
        });

        // Unload while the owner task is already accepted but still queued behind the blocker.
        registry.unregisterWorld("alpha");
        release.countDown();

        assertTrue("queued task must still run and fail", checked.await(5, TimeUnit.SECONDS));
        assertTrue("queued task must observe the stale generation",
                failure.get() instanceof WorldOwnerRegistry.StaleOwnerGenerationException);
    }

    @Test
    public void entityRegistrationRejectsDuplicatesAndRemovalRequiresOwner() throws Exception {
        final WorldOwnerRegistry.WorldOwner owner = registry.registerWorld("alpha", alphaRegion);
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerEntity(owner, 55);
            }
        }));

        final Throwable duplicate = runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerEntity(owner, 55);
            }
        });
        assertTrue("duplicate entity registration must fail fast",
                duplicate instanceof IllegalStateException);

        Throwable wrongThread = null;
        try {
            registry.unregisterEntity(owner, 55);
        } catch (Throwable t) {
            wrongThread = t;
        }
        assertTrue("entity removal from a non-owner must fail fast",
                wrongThread instanceof WorldOwnerRegistry.OwnerViolationException);
        assertEquals("wrong-thread removal must not unbind", "alpha", registry.entityWorld(55));

        final boolean[] removed = new boolean[1];
        final boolean[] secondRemoval = new boolean[1];
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                removed[0] = registry.unregisterEntity(owner, 55);
                secondRemoval[0] = registry.unregisterEntity(owner, 55);
            }
        }));
        assertTrue(removed[0]);
        assertFalse("entity removal must be idempotent", secondRemoval[0]);
        assertNull(registry.entityWorld(55));
    }

    @Test
    public void playerQuitRequiresWorldOwnerAndUnbindsEntity() throws Exception {
        final WorldOwnerRegistry.WorldOwner owner = registry.registerWorld("alpha", alphaRegion);
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerEntity(owner, 7);
            }
        }));

        Throwable wrongThread = null;
        try {
            registry.onPlayerQuit(owner, 7);
        } catch (Throwable t) {
            wrongThread = t;
        }
        assertTrue(wrongThread instanceof WorldOwnerRegistry.OwnerViolationException);
        assertEquals("wrong-thread quit must not unbind", "alpha", registry.entityWorld(7));

        final boolean[] quit = new boolean[1];
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                quit[0] = registry.onPlayerQuit(owner, 7);
            }
        }));
        assertTrue(quit[0]);
        assertNull(registry.entityWorld(7));

        final boolean[] secondQuit = new boolean[1];
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                secondQuit[0] = registry.onPlayerQuit(owner, 7);
            }
        }));
        assertFalse("quit must be idempotent", secondQuit[0]);
    }

    @Test
    public void worldUnloadReleasesEntitiesAndChunks() throws Exception {
        final WorldOwnerRegistry.WorldOwner owner = registry.registerWorld("alpha", alphaRegion);
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerEntity(owner, 1);
                registry.registerEntity(owner, 2);
                registry.registerChunk(owner, 0, 0);
                registry.registerChunk(owner, 1, 0);
            }
        }));

        final WorldOwnerRegistry.WorldUnload report = registry.unregisterWorld("alpha");
        assertEquals(2, report.entitiesUnbound());
        assertEquals(2, report.chunksUnloaded());
        assertTrue(registry.entitiesIn("alpha").isEmpty());
        assertEquals(0, registry.chunkCount("alpha"));
        assertNull(registry.entityWorld(1));
        assertNull(registry.entityWorld(2));
    }

    @Test
    public void crossWorldTransferUsesImmutableTicketAndTargetGeneration() throws Exception {
        final WorldOwnerRegistry.WorldOwner alpha = registry.registerWorld("alpha", alphaRegion);
        final WorldOwnerRegistry.WorldOwner beta = registry.registerWorld("beta", betaRegion);
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerEntity(alpha, 42);
            }
        }));

        final WorldOwnerRegistry.TransferTicket[] ticket = new WorldOwnerRegistry.TransferTicket[1];
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                ticket[0] = registry.beginTransfer(alpha, 42, "beta");
            }
        }));
        assertNotNull(ticket[0]);
        assertEquals("world-transfer", ticket[0].messageType());
        assertEquals("alpha", ticket[0].fromWorld());
        assertEquals("beta", ticket[0].toWorld());
        final RegionMessage<WorldOwnerRegistry.TransferTicket> envelope =
                RegionMessage.of("alpha", 1L, ticket[0]);
        assertTrue("transfer ticket must pass the immutable message validator",
                envelope.payload() == ticket[0]);
        assertNull("source binding must be released before the ticket crosses owners",
                registry.entityWorld(42));

        final boolean[] accepted = new boolean[1];
        assertNull(runOn(betaRegion, new Runnable() {
            @Override
            public void run() {
                accepted[0] = registry.completeTransfer(ticket[0]);
            }
        }));
        assertTrue(accepted[0]);
        assertEquals("beta", registry.entityWorld(42));
        assertEquals(1L, registry.transfersCompleted());

        // The target generation is captured in the ticket; unloading the target must reject it.
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerEntity(alpha, 43);
            }
        }));
        final WorldOwnerRegistry.TransferTicket[] staleTicket = new WorldOwnerRegistry.TransferTicket[1];
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                staleTicket[0] = registry.beginTransfer(alpha, 43, "beta");
            }
        }));
        registry.unregisterWorld("beta");

        final boolean[] staleAccepted = new boolean[1];
        assertNull(runOn(betaRegion, new Runnable() {
            @Override
            public void run() {
                staleAccepted[0] = registry.completeTransfer(staleTicket[0]);
            }
        }));
        assertFalse("stale target must not accept the transfer", staleAccepted[0]);
        assertNull("rejected transfer must not bind on any owner", registry.entityWorld(43));
        assertEquals(1L, registry.transfersRejected());
    }

    @Test
    public void worldRuntimeReRegistersARetiredWorldOnItsExistingOwnerDomain() throws Exception {
        final RegionScheduler runtimeScheduler = new RegionScheduler();
        final WorldRegionRuntime runtime = new WorldRegionRuntime(runtimeScheduler,
                new WorldRegionRuntime.PlanFunction() {
                    @Override
                    public TrackerVisibilityPlanner.Plan plan(TrackerSnapshot current, TrackerSnapshot previous) {
                        return new TrackerVisibilityPlanner.Plan(current.generation(), current.regionId(),
                                Collections.<Integer, List<Integer>>emptyMap(),
                                Collections.<Integer, List<Integer>>emptyMap(),
                                Collections.<Integer>emptyList(), Collections.<Integer>emptyList(),
                                Collections.<Integer>emptyList());
                    }
                },
                new WorldRegionRuntime.PlanApplier() {
                    @Override
                    public boolean apply(String worldName, TrackerVisibilityPlanner.Plan plan) {
                        return true;
                    }
                }, 2, 4);
        try {
            assertTrue(runtime.registerWorld("alpha"));
            final String ownerBefore = awaitOwnerName(runtime, "alpha");
            assertNotNull(ownerBefore);

            final List<String> retired = runtime.retainWorlds(Collections.<String>emptyList());
            assertEquals(Collections.singletonList("alpha"), retired);
            assertFalse(runtime.isRegistered("alpha"));

            assertTrue("a retired world must be re-registerable", runtime.registerWorld("alpha"));
            assertEquals("re-register must reuse the existing owner domain", 1, runtime.domainCount());
            assertEquals(ownerBefore, awaitOwnerName(runtime, "alpha"));
        } finally {
            runtime.shutdownDrain(5000);
        }
    }

    private static String awaitOwnerName(WorldRegionRuntime runtime, String worldName) throws InterruptedException {
        String owner = null;
        for (int i = 0; i < 200 && owner == null; i++) {
            owner = runtime.ownerThreadName(worldName);
            if (owner == null) {
                Thread.sleep(5L);
            }
        }
        return owner;
    }

    private Throwable runOn(OwnedRegion region, Runnable body) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        scheduler.schedule(region, new Runnable() {
            @Override
            public void run() {
                try {
                    body.run();
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }
        });
        assertTrue("owner task did not complete", done.await(5, TimeUnit.SECONDS));
        return failure.get();
    }
}
