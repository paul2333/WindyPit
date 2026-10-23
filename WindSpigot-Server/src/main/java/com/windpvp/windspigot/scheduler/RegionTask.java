
package com.windpvp.windspigot.scheduler;

/**
 * One unit of region work: strictly ordered inside its region, carrying sequence, region id and
 * the region generation it was scheduled against so stale work can be identified downstream.
 */
public final class RegionTask {

    private final long sequence;
    private final String regionId;
    private final long generation;
    private final Runnable body;
    private final TaskRetirementToken token;

    public RegionTask(long sequence, String regionId, long generation, Runnable body, TaskRetirementToken token) {
        this.sequence = sequence;
        this.regionId = regionId;
        this.generation = generation;
        this.body = body;
        this.token = token;
    }

    public long sequence() {
        return sequence;
    }

    public String regionId() {
        return regionId;
    }

    public long generation() {
        return generation;
    }

    public TaskRetirementToken token() {
        return token;
    }

    /**
     * 执行任务体。
     *
     * <p>【2026-10-24 修复：检查与执行之间有竞态窗口】</p>
     *
     * <p>原来是：</p>
     * <pre>
     *   if (token != null &amp;&amp; token.isRetired()) return false;   // ← 检查
     *   body.run();                                            // ← 使用
     * </pre>
     *
     * <p>{@code retire()} 可能正好落在这两步之间 —— 于是
     * {@code EntityScheduler.unregisterEntity} 已经判定"这个实体没了、别再跑它的活"，
     * 而 body 照样执行，去改动一个已不存在的实体（陈旧工作）。
     * 轻则空转，重则改到复用同一 id 的新实体。</p>
     *
     * <p>现在改为向 token 抢占执行权（单变量 CAS）：抢占成功才跑，
     * 抢占失败说明已被取消，直接跳过。二者真正互斥。</p>
     *
     * @return true if the body ran, false when the task was retired before execution.
     */
    public boolean execute() {
        if (token != null && !token.tryBeginExecution()) {
            return false;
        }
        body.run();
        if (token != null) {
            token.markCompleted();
        }
        return true;
    }
}

