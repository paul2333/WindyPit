package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * 回归锁：{@link TaskRetirementToken} 的 {@code retire} 与执行必须真正互斥。
 *
 * <p><b>原来的缺陷</b></p>
 *
 * <p>{@link RegionTask#execute()} 是：</p>
 * <pre>
 *   if (token != null &amp;&amp; token.isRetired()) return false;   // ← 检查
 *   body.run();                                            // ← 使用
 * </pre>
 *
 * <p>{@code retire()} 可能落在检查之后、执行之前。于是
 * {@code EntityScheduler.unregisterEntity} 已经判定"实体没了、别跑它的活"，
 * body 照样执行 —— 去改动一个已不存在的实体（陈旧工作），
 * 重则改到复用同一 id 的新实体。</p>
 *
 * <p>修复把两个 volatile 标志换成一个单变量状态机（PENDING/RUNNING/RETIRED），
 * 用 CAS 让"抢占执行"与"取消"二选一。</p>
 */
public class TaskRetirementTokenRaceTest {

    /** 先取消后执行：body 绝不能跑。 */
    @Test
    public void retireBeforeExecutePreventsBody() {
        final TaskRetirementToken token = TaskRetirementToken.create();
        final AtomicInteger runs = new AtomicInteger();
        final RegionTask task = new RegionTask(1L, "arena", 1L, runs::incrementAndGet, token);

        token.retire();

        assertFalse("已取消的任务不应执行", task.execute());
        assertEquals("body 不应被调用", 0, runs.get());
        assertTrue("应被识别为已取消", token.isRetired());
    }

    /** 先执行后取消：body 跑一次，且不会被重复执行。 */
    @Test
    public void executeThenRetireKeepsSingleRun() {
        final TaskRetirementToken token = TaskRetirementToken.create();
        final AtomicInteger runs = new AtomicInteger();
        final RegionTask task = new RegionTask(1L, "arena", 1L, runs::incrementAndGet, token);

        assertTrue("首次执行应当成功", task.execute());
        assertEquals(1, runs.get());

        token.retire();
        assertFalse("同一 token 不得执行第二次", task.execute());
        assertEquals("body 仍只跑过一次", 1, runs.get());
    }

    /**
     * 并发竞态：多个线程同时 {@code execute()}，body 必须**恰好执行一次**。
     *
     * <p>这是"抢占执行权"的核心保证 —— 没有它，排队的任务可能被重复提交执行。</p>
     */
    @Test
    public void concurrentExecuteRunsBodyExactlyOnce() throws Exception {
        final TaskRetirementToken token = TaskRetirementToken.create();
        final AtomicInteger runs = new AtomicInteger();
        final RegionTask task = new RegionTask(1L, "arena", 1L, runs::incrementAndGet, token);

        final int threads = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final Thread worker = new Thread(() -> {
                try {
                    start.await();
                    task.execute();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }
        start.countDown();
        done.await();

        assertEquals("并发执行下 body 必须恰好跑一次", 1, runs.get());
    }

    /**
     * 并发"取消 vs 执行"：结果只允许两种，不能出现"取消了但 body 跑了两次"这种混态。
     */
    @Test
    public void concurrentRetireAndExecuteAreMutuallyExclusive() throws Exception {
        for (int round = 0; round < 200; round++) {
            final TaskRetirementToken token = TaskRetirementToken.create();
            final AtomicInteger runs = new AtomicInteger();
            final RegionTask task = new RegionTask(1L, "arena", 1L, runs::incrementAndGet, token);

            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(2);
            Thread exec = new Thread(() -> {
                try {
                    start.await();
                    task.execute();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            Thread cancel = new Thread(() -> {
                try {
                    start.await();
                    token.retire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            exec.setDaemon(true);
            cancel.setDaemon(true);
            exec.start();
            cancel.start();
            start.countDown();
            done.await();

            final int actual = runs.get();
            assertTrue("body 最多执行一次，实测 " + actual + "（round=" + round + "）", actual <= 1);
        }
    }
}
