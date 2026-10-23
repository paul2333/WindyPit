package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 回归锁：{@link EntityScheduler} 的 {@code pendingTokens} 必须只统计**在途**任务。
 *
 * <p><b>这个缺陷会让长生命周期实体的调度永久失效</b></p>
 *
 * <p>{@code pendingTokens} 原来只在 {@link EntityScheduler#unregisterEntity} 里清空。
 * 而实体在整个生命周期内会不断 {@code scheduleForEntity}，每次都往列表里加一个 token
 * —— <b>执行完也不摘除</b>。累计排到容量上限（默认 256）之后，该实体就永远落进
 * 溢出分支：每次调度都被 {@code retire()}，body 一次也不执行。</p>
 *
 * <p>256 次调度对玩家实体来说几分钟就用完了，之后该实体所有 region 任务静默失效
 * —— 没有异常、没有日志，只是"什么都没发生"。</p>
 *
 * <p>修复方式：每次调度前先摘掉已完成/已退休的 token，让容量限制的是
 * <b>在途任务数</b>而不是实体寿命内的累计次数。</p>
 */
public class EntitySchedulerTokenReclaimTest {

    private RegionScheduler scheduler;
    private OwnedRegion arena;

    private EntityScheduler newScheduler(int capacity) {
        scheduler = new RegionScheduler();
        arena = OwnedRegion.arenaPrimary("arena", 64);
        scheduler.register(arena);
        return new EntityScheduler(scheduler, scheduler.diagnostics(), capacity);
    }

    /**
     * 核心回归用例：容量为 2，但连续调度 5 次且每次都已完成 ——
     * 修复后全部受理；修复前第 3 次起就会被 retire。
     */
    @Test
    public void completedTokensDoNotExhaustCapacity() {
        final EntityScheduler entityScheduler = newScheduler(2);
        entityScheduler.registerEntity(7, arena);

        final int total = 5;
        int accepted = 0;
        for (int i = 0; i < total; i++) {
            TaskRetirementToken token = entityScheduler.scheduleForEntity(7, () -> { });
            if (!token.isRetired()) {
                accepted++;
                // 模拟"这个任务已经跑完了"：让下一次调度能把它摘掉。
                token.markCompleted();
            }
        }

        assertEquals("已完成的任务不应占着容量 —— " + total + " 次调度里被拒"
                        + (total - accepted) + " 次；说明 pendingTokens 没回收已完成 token",
                total, accepted);
    }

    /**
     * 反向用例：**真正的在途堆积**仍然必须被限制。
     *
     * <p>如果修复写成了"永远 clean 或永远不检查"，这条会失败 ——
     * 容量语义必须保留，只是不该被历史任务占满。</p>
     */
    @Test
    public void inFlightTasksStillRespectCapacity() {
        final EntityScheduler entityScheduler = newScheduler(2);
        entityScheduler.registerEntity(8, arena);

        final TaskRetirementToken first = entityScheduler.scheduleForEntity(8, () -> { });
        final TaskRetirementToken second = entityScheduler.scheduleForEntity(8, () -> { });
        // 前两个都在途（未完成、未退休），应占满容量。
        final TaskRetirementToken third = entityScheduler.scheduleForEntity(8, () -> { });

        assertFalse("第一个在途任务不应被拒", first.isRetired());
        assertFalse("第二个在途任务不应被拒", second.isRetired());
        assertTrue("在途任务已占满容量，第三个必须被拒（retire）", third.isRetired());
        assertTrue("被拒次数应被计数", entityScheduler.getEntityOverflow(8) >= 1);
    }

    /**
     * 退休（显式取消）的 token 同样应立即让出容量。
     */
    @Test
    public void retiredTokensReleaseCapacity() {
        final EntityScheduler entityScheduler = newScheduler(1);
        entityScheduler.registerEntity(9, arena);

        final TaskRetirementToken first = entityScheduler.scheduleForEntity(9, () -> { });
        assertFalse(first.isRetired());
        // 显式退休：任务不会执行，但也不该继续占容量。
        first.retire();

        final TaskRetirementToken second = entityScheduler.scheduleForEntity(9, () -> { });
        assertFalse("已退休的 token 不应占着容量，新任务应被受理", second.isRetired());
    }
}
