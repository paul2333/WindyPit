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
        // 【2026-10-24 修复：submitWorld 现在写在 if 条件里，不带分号】
        //
        // 原断言找的是 "worldRegions.submitWorld(worldserver);"，但实现已改成
        //     if (worldRegions.submitWorld(worldserver)) { ... }
        // —— 返回值被用来决定要不要刷新"计划存活"时间戳，所以是条件调用而不是语句。
        // 实现是对的，是这条源码契约没跟上，导致远端 mvn test 直接失败。
        final int submit = src.indexOf("worldRegions.submitWorld(worldserver)");
        final int trackerForApply = tracker;
        assertTrue("applyPending must exist", apply >= 0);
        assertTrue("tracker step must exist", tracker >= 0);
        assertTrue("submitWorld must exist", submit >= 0);
        assertTrue("plan must be applied before the tracker step", apply < trackerForApply);
        assertTrue("snapshot must be submitted after the tracker step", submit > trackerForApply);
        assertEquals("applyPending must be called exactly once", 1, count(src, "worldRegions.applyPending(worldName);"));
        assertEquals("submitWorld must be called exactly once", 1, count(src, "worldRegions.submitWorld(worldserver)"));
    }

    @Test
    public void trackerRunsMovementOnlyWhenPlanDriven() throws IOException {
        final String src = read(ENTITY_TRACKER);
        assertTrue("plan-driven flag must exist", src.contains("planDrivenVisibility"));

        // 【2026-10-24 修复：本断言此前锁定的是已被移除的分片写法】
        //
        // 原来断言 `entry.updateMovementOnly(shard++ % shardCount);`。但 `updatePlayers()`
        // 已被重构为**单写者**：注释写着 "The old per-entry shard index was only a
        // packet-queue selector for the removed async tracker; it added a modulo per entity
        // and multiplied per-connection queues without any concurrency. One FIFO per
        // connection is both cheaper and strictly ordered."
        //
        // 也就是说 shard 索引随异步 tracker 一起被删了，调用点变成 `xxx(0)`。
        // 实现是对的，是这条源码契约测试没跟上 —— 它让远端 `mvn test` 直接失败。
        //
        // 现在改为断言"两条路径都存在且传 0"，既保住原有意图
        //（plan-driven 走 movement-only、否则走 legacy update），
        // 又不再绑死已废弃的分片表达式。
        assertTrue("movement-only path must be selected for plan-driven worlds",
                src.contains("entry.updateMovementOnly(0);"));
        assertTrue("legacy update path must remain the default",
                src.contains("entry.update(0);"));
        // 分片索引不应再回来：它是已移除异步 tracker 的遗留物。
        assertFalse("shard index must not come back (async tracker was removed)",
                src.contains("shard++ % shardCount"));
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
