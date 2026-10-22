
package com.windpvp.windspigot.config;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Test WindyPitConfig loading from real files (W1-C).
 */
public class WindyPitConfigLoadingTest {

    @Test
    public void testDefaultsWhenFileIsMissing() throws IOException {
        File nonExistent = new File("this-file-does-not-exist.yml");
        assertFalse(nonExistent.exists());

        WindyPitConfig config = WindyPitConfig.load(nonExistent);

        // 2026-10-22: the shipped default is now REGION with 4 owner threads.
        // COMPAT is still available as an explicit opt-out (and via rollback flags),
        // but a missing file must not silently disable all off-thread planning work.
        assertEquals(WindyPitConfig.ThreadingMode.REGION, config.threading().mode());
        assertEquals(WindyPitConfig.ArenaOwner.PRIMARY, config.threading().arenaOwner());
        assertEquals(4, config.threading().regionThreads());
        assertTrue(config.features().mechanismPruning());
        assertFalse(config.rollback().forcePrimaryThreadArena());
    }

    @Test
    public void testLoadFromRealFile() throws IOException {
        File windyPitYml = new File("windypit.yml");
        if (!windyPitYml.exists()) {
            // Skip if file doesn't exist in test environment
            return;
        }

        WindyPitConfig config = WindyPitConfig.load(windyPitYml);

        assertNotNull(config);
        assertNotNull(config.threading());
        assertNotNull(config.features());
        assertNotNull(config.rollback());
    }

    @Test
    public void testThreadsResolution() {
        WindyPitConfig config = WindyPitConfig.defaults();

        // "auto" should resolve to the provided default
        int resolved = config.threading().resolveThreads("auto", 4);
        assertEquals(4, resolved);

        // Numeric string should parse
        resolved = config.threading().resolveThreads("8", 4);
        assertEquals(8, resolved);

        // Invalid should fallback to default
        resolved = config.threading().resolveThreads("invalid", 4);
        assertEquals(4, resolved);
    }
}

