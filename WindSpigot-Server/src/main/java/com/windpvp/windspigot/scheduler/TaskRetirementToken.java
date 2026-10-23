
package com.windpvp.windspigot.scheduler;

/**
 * Retirement handle for a scheduled unit of work. Once retired the task body never executes;
 * the scheduler records the skip in diagnostics. Completed marks a body that already ran, so
 * lifecycle code (entity removal, world unload) can retire exactly the pending work.
 *
 * <p>【2026-10-24】内部改为<b>单变量状态机 + CAS</b>：{@code PENDING → RUNNING} 与
 * {@code PENDING → RETIRED} 只能有一个成功。原来用两个独立的 volatile 标志，
 * "先检查 isRetired() 再 body.run()" 之间存在窗口，retire 落在窗口里时 body 仍会执行
 * —— 那意味着实体已被 unregisterEntity 移除之后，它的排队工作还在跑。
 * 详见 {@link #tryBeginExecution()}。</p>
 */
public final class TaskRetirementToken {

    /** 尚未被任何一方占有。 */
    private static final int PENDING = 0;
    /** 已被执行方占有（body 正在跑或已跑完）。 */
    private static final int RUNNING = 1;
    /** 已被取消，body 不得执行。 */
    private static final int RETIRED = 2;

    /**
     * 唯一状态位。
     *
     * <p>用一个变量而不是"retired + started"两个变量，是因为两个变量的 CAS 无法表达
     * "二选一"：无论先 CAS 哪个，另一方都可能在两步之间插入。
     * 单变量下 {@code compareAndSet(PENDING, RUNNING)} 与
     * {@code compareAndSet(PENDING, RETIRED)} 是真正的互斥。</p>
     */
    private final java.util.concurrent.atomic.AtomicInteger state =
            new java.util.concurrent.atomic.AtomicInteger(PENDING);

    private volatile boolean completed;

    public static TaskRetirementToken create() {
        return new TaskRetirementToken();
    }

    /**
     * 取消该任务。
     *
     * <p>若执行方已经抢到执行权（状态已是 {@link #RUNNING}），本次 retire 不会回滚
     * 已经开始的工作 —— 那是无法撤销的；但它仍然把状态置为 RETIRED，
     * 供后续查询（{@code isRetired()}）与 {@code unregisterEntity} 统计。</p>
     */
    public void retire() {
        // 成功从 PENDING 抢到则直接置 RETIRED；
        // 已被执行方占有时，直接覆盖为 RETIRED 以便 isRetired() 如实反映"调用方要求取消"。
        if (!state.compareAndSet(PENDING, RETIRED)) {
            state.set(RETIRED);
        }
    }

    public boolean isRetired() {
        return state.get() == RETIRED;
    }

    /**
     * 抢占执行权：**当且仅当**尚未退休且未被占用时返回 true。
     *
     * <p>【2026-10-24 新增】给 {@code RegionTask.execute()} 用。</p>
     *
     * <p>与 {@link #retire()} 通过同一个状态变量做 CAS，因此二者真正互斥：</p>
     * <ul>
     *   <li>先执行后取消：body 已经开始，取消无法撤回（这是物理事实）；</li>
     *   <li>先取消后执行：CAS 从 PENDING 失败（当前是 RETIRED），返回 false，body 不跑。</li>
     * </ul>
     *
     * <p>这消除了原来 {@code if (isRetired()) ...; body.run();} 之间的检查-使用窗口。</p>
     *
     * @return true 表示本次调用成功占有执行权，调用方必须执行 body
     */
    boolean tryBeginExecution() {
        return state.compareAndSet(PENDING, RUNNING);
    }

    /** 是否已被执行方占有（不代表一定跑完）。 */
    public boolean isStarted() {
        return state.get() == RUNNING || completed;
    }

    void markCompleted() {
        completed = true;
    }

    public boolean isCompleted() {
        return completed;
    }
}
