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
 * W1-SHUTDOWN-DRAIN: MinecraftServer.stop() must wire the shutdown sequence in a fixed order:
 * WindyPit scheduler drain -> plugin disable -> network drain/stop -> world save -> chunk IO close
 * -> CraftScheduler async pool shutdown.
 */
public class ShutdownDrainWiringSourceContractTest {

    private static final Path SERVER_SRC = Paths.get("src", "main", "java", "net", "minecraft", "server",
            "MinecraftServer.java");

    @Test
    public void stopWiresEveryShutdownDrainStepInOrder() throws IOException {
        final String source = readServerSource();

        final int schedulerDrain = indexOf(source, "windyTick.shutdownDrain(30000L)");
        final int disablePlugins = indexOf(source, "this.server.disablePlugins()");
        final int networkDrain = indexOf(source, "drainFastNetworkQueues()");
        final int stopServer = indexOf(source, "this.aq().stopServer()");
        final int saveWorlds = indexOf(source, "this.saveChunks(false)");
        final int chunkIoClose = indexOf(source, "ChunkIOExecutor.close(");
        final int schedulerShutdown = indexOf(source, "shutdownAsyncExecutor(");

        assertEquals("scheduler drain must be wired exactly once", 1, countOccurrences(source, "windyTick.shutdownDrain(30000L)"));
        assertEquals("plugin disable must be wired exactly once", 1, countOccurrences(source, "this.server.disablePlugins()"));
        assertEquals("network drain must be wired exactly once", 1, countOccurrences(source, "drainFastNetworkQueues()"));
        assertEquals("chunk IO close must be wired exactly once", 1, countOccurrences(source, "ChunkIOExecutor.close("));
        assertEquals("scheduler async shutdown must be wired exactly once", 1, countOccurrences(source, "shutdownAsyncExecutor("));

        assertTrue("scheduler drain must precede plugin disable", schedulerDrain < disablePlugins);
        assertTrue("plugin disable must precede the network drain", disablePlugins < networkDrain);
        assertTrue("network drain must precede ServerConnection.stopServer", networkDrain < stopServer);
        assertTrue("ServerConnection.stopServer must precede world saving", stopServer < saveWorlds);
        assertTrue("world saving must precede the chunk IO close", saveWorlds < chunkIoClose);
        assertTrue("chunk IO close must precede the scheduler async shutdown", chunkIoClose < schedulerShutdown);
    }

    private static int indexOf(String source, String needle) {
        final int index = source.indexOf(needle);
        assertTrue("missing shutdown wiring: " + needle, index >= 0);
        return index;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String readServerSource() throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        for (int level = 0; level < 8 && directory != null; level++) {
            final Path candidate = directory.resolve(SERVER_SRC);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new AssertionError("MinecraftServer source not found from " + Paths.get("").toAbsolutePath());
    }
}
