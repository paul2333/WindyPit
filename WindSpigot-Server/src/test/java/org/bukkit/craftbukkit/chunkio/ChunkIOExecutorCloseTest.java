package org.bukkit.craftbukkit.chunkio;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.RejectedExecutionException;

import org.junit.Test;

/**
 * W1-SHUTDOWN-DRAIN: the static chunk IO executor must be closable from the server shutdown path.
 */
public class ChunkIOExecutorCloseTest {

    @Test
    public void closeShutsDownTheStaticExecutorAndRejectsNewChunkLoads() {
        assertTrue("an idle chunk IO executor must terminate inside the timeout", ChunkIOExecutor.close(5000L));
        assertTrue(ChunkIOExecutor.isClosed());
        assertTrue("close must be idempotent", ChunkIOExecutor.close(5000L));

        try {
            ChunkIOExecutor.queueChunkLoad(null, null, null, 0, 0, new Runnable() {
                @Override
                public void run() {
                }
            });
            fail("a closed chunk IO executor must reject new chunk loads");
        } catch (RejectedExecutionException expected) {
            // expected
        }
    }
}
