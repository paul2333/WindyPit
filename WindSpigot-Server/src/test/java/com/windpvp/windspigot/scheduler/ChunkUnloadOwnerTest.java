package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Chunk load/unload owner and generation contract (Prompt 70). Chunk keys are per world, every
 * unload must run on that world's owner, and an unload after world unload must fail fast instead
 * of deleting a same-coordinate chunk from a later world generation.
 */
public class ChunkUnloadOwnerTest {

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
    public void chunkRegistrationAndUnloadMustRunOnTheWorldOwner() throws Exception {
        final WorldOwnerRegistry.WorldOwner alpha = registry.registerWorld("alpha", alphaRegion);
        final boolean[] firstLoad = new boolean[1];
        final boolean[] duplicateLoad = new boolean[1];
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                firstLoad[0] = registry.registerChunk(alpha, 10, -3);
                duplicateLoad[0] = registry.registerChunk(alpha, 10, -3);
            }
        }));
        assertTrue("first registration must load the chunk", firstLoad[0]);
        assertFalse("duplicate registration must be idempotent", duplicateLoad[0]);
        assertTrue(registry.isChunkLoaded("alpha", 10, -3));

        Throwable wrongThread = null;
        try {
            registry.unloadChunk(alpha, 10, -3);
        } catch (Throwable t) {
            wrongThread = t;
        }
        assertTrue("chunk unload from a non-owner must fail fast",
                wrongThread instanceof WorldOwnerRegistry.OwnerViolationException);
        assertTrue("wrong-thread unload must not remove the chunk",
                registry.isChunkLoaded("alpha", 10, -3));

        final boolean[] unloaded = new boolean[1];
        final boolean[] secondUnload = new boolean[1];
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                unloaded[0] = registry.unloadChunk(alpha, 10, -3);
                secondUnload[0] = registry.unloadChunk(alpha, 10, -3);
            }
        }));
        assertTrue(unloaded[0]);
        assertFalse("unload must be idempotent", secondUnload[0]);
        assertFalse(registry.isChunkLoaded("alpha", 10, -3));
    }

    @Test
    public void chunkKeysAreScopedToTheirWorld() throws Exception {
        final WorldOwnerRegistry.WorldOwner alpha = registry.registerWorld("alpha", alphaRegion);
        final WorldOwnerRegistry.WorldOwner beta = registry.registerWorld("beta", betaRegion);
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerChunk(alpha, 5, 7);
            }
        }));
        assertNull(runOn(betaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerChunk(beta, 5, 7);
            }
        }));

        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.unloadChunk(alpha, 5, 7);
            }
        }));
        assertFalse("alpha chunk must be unloaded", registry.isChunkLoaded("alpha", 5, 7));
        assertTrue("same-coordinate beta chunk must stay loaded", registry.isChunkLoaded("beta", 5, 7));

        Throwable crossWorld = null;
        try {
            registry.unloadChunk(beta, 5, 7);
        } catch (Throwable t) {
            crossWorld = t;
        }
        assertTrue("beta chunk must only be unloaded by the beta owner",
                crossWorld instanceof WorldOwnerRegistry.OwnerViolationException);
        assertTrue(registry.isChunkLoaded("beta", 5, 7));
    }

    @Test
    public void chunkUnloadAfterWorldUnloadIsRejectedAsStale() throws Exception {
        final WorldOwnerRegistry.WorldOwner alpha = registry.registerWorld("alpha", alphaRegion);
        assertNull(runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerChunk(alpha, 1, 2);
            }
        }));

        final WorldOwnerRegistry.WorldUnload report = registry.unregisterWorld("alpha");
        assertEquals(1, report.chunksUnloaded());
        assertFalse(registry.isChunkLoaded("alpha", 1, 2));

        final Throwable staleUnload = runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.unloadChunk(alpha, 1, 2);
            }
        });
        assertTrue("old-generation unload must fail fast",
                staleUnload instanceof WorldOwnerRegistry.StaleOwnerGenerationException);

        final Throwable staleLoad = runOn(alphaRegion, new Runnable() {
            @Override
            public void run() {
                registry.registerChunk(alpha, 3, 4);
            }
        });
        assertTrue("old-generation load must fail fast",
                staleLoad instanceof WorldOwnerRegistry.StaleOwnerGenerationException);
        assertFalse(registry.isChunkLoaded("alpha", 3, 4));
        assertTrue(registry.staleRejections() >= 2L);
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
