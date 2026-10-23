package com.windpvp.windspigot.world;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * 源码级守卫：{@code EntityTracker} 的"计划流停滞"判定必须用<b>单调时钟</b>。
 *
 * <p><b>为什么这条重要</b></p>
 *
 * <p>{@code planStalled()} 是"计划流断掉后自动退回原版可见性"的兜底触发点。
 * 它原来是 {@code System.currentTimeMillis() - last > PLAN_STALL_MILLIS}。</p>
 *
 * <p>墙上时钟会被 NTP 校正<b>往回拨</b>。一旦发生，差值变成负数、永远不可能
 * 大于阈值 —— 兜底逻辑恰好在这个时刻失效：tracker 永远走 {@code updateMovementOnly}、
 * add/remove 永不执行，那个世界的实体对所有人永久不可见。</p>
 *
 * <p>改用 {@link System#nanoTime()} 后差值是单调的，不会为负。
 * 本测试钉住这一点，防止有人"图直观"改回墙钟。</p>
 */
public class EntityTrackerMonotonicClockContractTest {

    private static final Path ENTITY_TRACKER =
            Paths.get("src", "main", "java", "net", "minecraft", "server", "EntityTracker.java");

    @Test
    public void stallDetectionUsesMonotonicClock() throws IOException {
        final String src = stripComments(read());
        assertTrue("停滞判定必须基于 nanoTime（单调时钟）",
                src.contains("System.nanoTime()"));
        assertFalse("停滞判定不得再用 currentTimeMillis —— NTP 回拨会让差值变负、兜底永久失效",
                src.contains("System.currentTimeMillis() - last"));
    }

    /**
     * 时间戳字段本身也不能再用墙钟取值。
     *
     * <p>只把比较改成 nanoTime、却仍然用 currentTimeMillis 写入基线，会让差值
     * 变成"纳秒 - 毫秒"的荒谬量级 —— 那是更糟的半吊子改法。</p>
     */
    @Test
    public void baselineTimestampAlsoUsesMonotonicClock() throws IOException {
        final String src = stripComments(read());
        assertFalse("基线也不能用 currentTimeMillis 写入",
                src.contains("lastPlanAppliedMillis = System.currentTimeMillis()"));
        assertTrue("基线必须由 nanoTime 写入", src.contains("lastPlanAppliedNanos = System.nanoTime()"));
    }

    /** 换算常量必须存在，否则 nanoTime 的纳秒值会被直接当毫秒比较。 */
    @Test
    public void nanosToMillisConversionExists() throws IOException {
        final String src = stripComments(read());
        assertTrue("必须显式把纳秒差换算成毫秒再与 PLAN_STALL_MILLIS 比较",
                src.contains("NANOS_PER_MILLI"));
    }

    // ---------------------------------------------------------------- helpers

    private static String read() throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        for (int level = 0; level < 8 && directory != null; level++) {
            final Path candidate = directory.resolve(ENTITY_TRACKER);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new AssertionError("source not found: " + ENTITY_TRACKER);
    }

    /** 剥掉注释：说明性注释里会提到 currentTimeMillis 等字样，不剥会造成误判。 */
    private static String stripComments(String source) {
        final StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        final int length = source.length();
        while (i < length) {
            final char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                while (i < length && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < length && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
