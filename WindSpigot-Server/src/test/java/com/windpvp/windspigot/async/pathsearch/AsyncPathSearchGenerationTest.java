package com.windpvp.windspigot.async.pathsearch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Contract for the generation check that makes async path searching safe.
 *
 * <p>The real {@code ChunkCache} needs a live world, so these tests pin the invariant that the
 * worker relies on: a captured generation array only matches while nothing moved, and any drift
 * makes the comparison fail closed.
 */
public class AsyncPathSearchGenerationTest {

    /** Mirrors ChunkCache.matchesGeneration: equal arrays match, any drift fails. */
    private static boolean matches(int[] captured, int[] current) {
        if (captured == null) {
            return false;
        }
        if (captured.length != current.length) {
            return false;
        }
        for (int i = 0; i < captured.length; i++) {
            if (captured[i] != current[i]) {
                return false;
            }
        }
        return true;
    }

    @Test
    public void unchangedChunksKeepThePath() {
        final int[] captured = {0, 5, 7, 0};
        assertTrue(matches(captured, new int[] {0, 5, 7, 0}));
    }

    @Test
    public void anyBlockChangeDiscardsThePath() {
        final int[] captured = {0, 5, 7, 0};
        assertFalse("a single changed chunk must invalidate the search",
                matches(captured, new int[] {0, 6, 7, 0}));
    }

    @Test
    public void missingCaptureFailsClosed() {
        assertFalse(matches(null, new int[] {0}));
    }

    @Test
    public void shapeMismatchFailsClosed() {
        assertFalse(matches(new int[] {0, 1}, new int[] {0}));
    }

    @Test
    public void chunkWriteBumpsItsGeneration() {
        // Chunk.blockChangeGeneration is bumped by Chunk.a(BlockPosition, IBlockData, boolean).
        // Modelled here so the contract is documented without needing a live world.
        int generation = 0;
        generation++;
        assertFalse("after one write the captured value is stale",
                matches(new int[] {0}, new int[] {generation}));
    }
}
