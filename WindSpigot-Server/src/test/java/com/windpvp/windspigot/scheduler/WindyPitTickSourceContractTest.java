
package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Source-level guard rails: MinecraftServer must wire the WindyPit tick integration exactly once,
 * at the seam directly after the parallel world tick, and must never call it elsewhere.
 */
public class WindyPitTickSourceContractTest {

    private static final Path SERVER_SRC = Paths.get("src", "main", "java", "net", "minecraft", "server", "MinecraftServer.java");

    @Test
    public void minecraftServerWiresThePipelineExactlyOnceAtTheWorldTickSeam() throws IOException {
        final String src = readServerSource();
        assertTrue("MinecraftServer must hold the WindyPit tick integration",
                src.contains("com.windpvp.windspigot.scheduler.WindyPitTickIntegration windyTick"));
        final int seam = src.indexOf("this.windyTick.onTickSeam();");
        assertTrue("MinecraftServer must call the tick seam", seam >= 0);
        assertEquals("the tick seam must exist exactly once", 1, countOccurrences(src, "this.windyTick.onTickSeam();"));
        final int worldTick = src.indexOf("this.worldTickerManager.tick();");
        assertTrue("the seam must sit after the world tick", worldTick >= 0 && seam > worldTick);
        assertTrue("the seam must be a WindSpigot-marked block", src.contains("WindyPit tick pipeline (W1-TICK-PIPELINE)"));
    }

    private static String readServerSource() throws IOException {
        final Path relative = SERVER_SRC;
        Path directory = Paths.get("").toAbsolutePath();
        for (int level = 0; level < 8 && directory != null; level++) {
            final Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new AssertionError("MinecraftServer source not found from " + Paths.get("").toAbsolutePath());
    }

    @Test
    public void trackerShadowFeedIsDisabledUntilSafePacketApplicationIsProven() throws IOException {
        // W1: TrackerShadowFeed.feed was previously wired but only built and discarded snapshots
        // (O(world entities) overhead, zero benefit). Per W1 acceptance: "若安全包应用无法证明，
        // 整体关闭该 feature". TrackerAsyncEngine/TrackerVisibilityPlanner exist and are tested;
        // safe NMS EntityTracker integration deferred to Wave 2.
        final String src = readServerSource();
        assertTrue("TrackerShadowFeed must not be called from MinecraftServer (disabled until W2)",
                !src.contains("TrackerShadowFeed.feed(this.worldServer)"));

        // TrackerShadowFeed class still exists for future integration
        Path trackerPath = Paths.get("src", "main", "java", "com", "windpvp", "windspigot", "tracker", "TrackerShadowFeed.java");
        Path directory = Paths.get("").toAbsolutePath();
        boolean found = false;
        for (int level = 0; level < 8 && directory != null; level++) {
            if (Files.exists(directory.resolve(trackerPath))) {
                found = true;
                break;
            }
            directory = directory.getParent();
        }
        assertTrue("TrackerShadowFeed class still exists for future integration", found);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}

