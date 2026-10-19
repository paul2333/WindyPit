
package com.windpvp.windspigot.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Source-level guard rails for the world tracker step. These assertions fail the build when the
 * guarded synchronous tracker step is replaced by an inline copy, an unconditional call, an
 * asynchronous tracker, or a scheduler hand-off inside the world tick.
 */
public class WorldTickerSourceContractTest {

    private static final Path WORLD_PACKAGE = Paths.get("src", "main", "java", "com", "windpvp", "windspigot", "world");

    @Test
    public void worldTickerDelegatesTheTrackerStepToTheGuardedHelper() throws IOException {
        String ticker = readModuleSource(WORLD_PACKAGE.resolve("WorldTicker.java"));

        assertTrue("WorldTicker must run the tracker step through the guarded helper",
                ticker.contains("WorldTrackerTick.run(this)"));
        assertTrue("WorldTicker must implement the helper target seam",
                ticker.contains("WorldTrackerTick.Target"));
        assertTrue("the tracker timing must be stopped from the target implementation",
                ticker.contains("worldserver.timings.tracker.stopTiming()"));

        int runIndex = ticker.indexOf("public void run()");
        int updateTrackerIndex = ticker.indexOf("public void updateTracker()");
        int updatePlayersIndex = ticker.indexOf("updatePlayers(");
        int stopTimingIndex = ticker.indexOf("public void stopTrackerTiming()");

        assertTrue("the tracker update must exist exactly once in WorldTicker", updatePlayersIndex >= 0);
        assertEquals("the tracker update must not be duplicated", 1, countOccurrences(ticker, "updatePlayers("));
        assertTrue("the tracker update must not be called from the tick body itself",
                runIndex >= 0 && updateTrackerIndex > runIndex);
        assertTrue("the tracker update must live in the guarded target method",
                updatePlayersIndex > updateTrackerIndex);
        if (stopTimingIndex >= 0) {
            assertTrue("the tracker update must sit inside updateTracker(), not in stopTrackerTiming()",
                    updatePlayersIndex < stopTimingIndex);
        }
        assertFalse("the world tick must not hand the tracker to a scheduler",
                ticker.contains("getScheduler") || ticker.contains("new Thread") || ticker.contains("Executor"));
    }

    @Test
    public void trackerStepKeepsTheTimingStopInAFinallyPath() throws IOException {
        String helper = readModuleSource(WORLD_PACKAGE.resolve("WorldTrackerTick.java"));

        int tryIndex = helper.indexOf("try {");
        int finallyIndex = helper.indexOf("} finally {");
        int stopIndex = helper.indexOf("target.stopTrackerTiming();");

        assertTrue("the tracker step must wrap the update in a try block", tryIndex >= 0);
        assertTrue("the tracker step must close the timing window from a finally path", finallyIndex > tryIndex);
        assertTrue("the timing stop must sit inside the finally path", stopIndex > finallyIndex);
        assertFalse("the tracker step must not swallow the tracker failure", helper.contains("catch"));
        assertEquals(1, countOccurrences(helper, "target.startTrackerTiming();"));
        assertEquals(1, countOccurrences(helper, "target.updateTracker();"));
        assertEquals(1, countOccurrences(helper, "target.stopTrackerTiming();"));
        assertEquals(1, countOccurrences(helper, "target.worldPlayerCount()"));
    }

    @Test
    public void asyncEntityTrackerStaysSynchronous() throws IOException {
        String async = readModuleSource(Paths.get("src", "main", "java", "com", "windpvp", "windspigot", "async",
                "entitytracker", "AsyncEntityTracker.java"));

        assertFalse("AsyncEntityTracker must not run the tracker update itself", async.contains("updatePlayers"));
        assertFalse("AsyncEntityTracker must not run per-player tracker updates", async.contains("updatePlayer("));
        assertFalse("AsyncEntityTracker must not own an executor or thread",
                async.contains("Executor") || async.contains("new Thread") || async.contains("CompletableFuture")
                        || async.contains("submit(") || async.contains("parallelStream"));
        assertFalse("AsyncEntityTracker must not touch Bukkit APIs", async.contains("Bukkit."));
    }

    private static int countOccurrences(String haystack, String needle) {
        int found = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            found++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return found;
    }

    private static String readModuleSource(Path moduleRelative) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        for (int level = 0; level < 8 && directory != null; level++) {
            Path candidate = directory.resolve(moduleRelative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new AssertionError("module source not found from " + Paths.get("").toAbsolutePath() + ": " + moduleRelative);
    }
}

