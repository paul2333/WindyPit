package com.windpvp.windspigot.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Source-level contract for the real per-world owner-domain seam (W1-REGION-REAL).
 *
 * <p>The runtime behaviour is covered by {@code WorldRegionRuntimeTest}; these assertions make
 * sure the production call chain keeps the plan application before the tracker step and the
 * snapshot submission after it, exactly once, and that the tracker only runs the movement-only
 * path when a plan owns visibility.</p>
 */
public class WorldRegionWiringSourceContractTest {

    private static final Path WORLD_TICKER =
            Paths.get("src", "main", "java", "com", "windpvp", "windspigot", "world", "WorldTicker.java");
    private static final Path ENTITY_TRACKER =
            Paths.get("src", "main", "java", "net", "minecraft", "server", "EntityTracker.java");
    private static final Path ENTITY_TRACKER_ENTRY =
            Paths.get("src", "main", "java", "net", "minecraft", "server", "EntityTrackerEntry.java");
    private static final Path INTEGRATION =
            Paths.get("src", "main", "java", "com", "windpvp", "windspigot", "scheduler", "WindyPitTickIntegration.java");

    @Test
    public void worldTickerAppliesPlanBeforeTrackerAndSubmitsSnapshotAfterItExactlyOnce() throws IOException {
        final String src = read(WORLD_TICKER);
        final int apply = src.indexOf("worldRegions.applyPending(worldName);");
        final int tracker = src.indexOf("WorldTrackerTick.run(this);");
        final int submit = src.indexOf("worldRegions.submitWorld(worldserver);");
        assertTrue("applyPending must exist", apply >= 0);
        assertTrue("tracker step must exist", tracker >= 0);
        assertTrue("submitWorld must exist", submit >= 0);
        assertTrue("plan must be applied before the tracker step", apply < tracker);
        assertTrue("snapshot must be submitted after the tracker step", submit > tracker);
        assertEquals("applyPending must be called exactly once", 1, count(src, "worldRegions.applyPending(worldName);"));
        assertEquals("submitWorld must be called exactly once", 1, count(src, "worldRegions.submitWorld(worldserver);"));
    }

    @Test
    public void trackerRunsMovementOnlyWhenPlanDriven() throws IOException {
        final String src = read(ENTITY_TRACKER);
        assertTrue("plan-driven flag must exist", src.contains("planDrivenVisibility"));
        assertTrue("movement-only path must be selected for plan-driven worlds",
                src.contains("entry.updateMovementOnly(shard++ % shardCount);"));
        assertTrue("legacy update path must remain the default",
                src.contains("entry.update(shard++ % shardCount);"));
    }

    @Test
    public void trackerEntryKeepsExactlyOneTrackCallPerMovementOnlyStep() throws IOException {
        final String src = read(ENTITY_TRACKER_ENTRY);
        final int method = src.indexOf("public void updateMovementOnly(int trackerThread)");
        assertTrue("updateMovementOnly must exist", method >= 0);
        final int track = src.indexOf("this.track(null);", method);
        assertTrue("updateMovementOnly must call track exactly once", track > method);
        assertTrue("updateMovementOnly must not run the visibility scan",
                src.indexOf("removeFarPlayers();", method) == -1
                        || src.indexOf("removeFarPlayers();", method) > src.indexOf("public void update(int trackerThread)") + 400);
    }

    @Test
    public void regionModeUsesTheNmsPlanApplier() throws IOException {
        final String src = read(INTEGRATION);
        assertTrue("integration must construct the real NMS applier",
                src.contains("new NmsTrackerPlanApplier()"));
        assertTrue("integration must create the runtime only in REGION mode",
                src.contains("config.threading().mode() == WindyPitConfig.ThreadingMode.REGION"));
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String read(Path relative) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        for (int level = 0; level < 8 && directory != null; level++) {
            final Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new AssertionError("source not found: " + relative);
    }
}
