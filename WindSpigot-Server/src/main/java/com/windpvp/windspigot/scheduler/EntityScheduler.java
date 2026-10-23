
package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity-scoped scheduling on top of the region scheduler. An entity is bound to exactly one
 * region; its tasks run on that region's owner thread. Unbinding retires all pending tasks so
 * removed entities never execute stale work.
 *
 * pendingTokens is bounded to prevent memory growth from long-lived entities with many scheduled
 * tasks. Overflow is counted but does not prevent scheduling (W1-G).
 */
public final class EntityScheduler {

    private static final int DEFAULT_PENDING_TOKENS_CAPACITY = 256;

    private static final class Binding {
        final OwnedRegion region;
        final List<TaskRetirementToken> pendingTokens = new ArrayList<TaskRetirementToken>();
        final int pendingTokensCapacity;
        long pendingTokensOverflow;

        Binding(OwnedRegion region, int pendingTokensCapacity) {
            this.region = region;
            this.pendingTokensCapacity = pendingTokensCapacity;
        }
    }

    private final ConcurrentHashMap<Integer, Binding> bindings = new ConcurrentHashMap<Integer, Binding>();
    private final RegionScheduler scheduler;
    private final SchedulerDiagnostics diag;
    private final int pendingTokensCapacity;

    public EntityScheduler(RegionScheduler scheduler, SchedulerDiagnostics diag) {
        this(scheduler, diag, DEFAULT_PENDING_TOKENS_CAPACITY);
    }

    public EntityScheduler(RegionScheduler scheduler, SchedulerDiagnostics diag, int pendingTokensCapacity) {
        this.scheduler = scheduler;
        this.diag = diag;
        this.pendingTokensCapacity = pendingTokensCapacity;
    }

    public void registerEntity(int entityId, OwnedRegion region) {
        if (bindings.putIfAbsent(entityId, new Binding(region, pendingTokensCapacity)) != null) {
            throw new IllegalStateException("entity already registered: " + entityId);
        }
    }

    public OwnedRegion regionOf(int entityId) {
        Binding b = bindings.get(entityId);
        return b == null ? null : b.region;
    }

    /** Schedules body on the entity's region owner; returns the token for explicit retirement. */
    public TaskRetirementToken scheduleForEntity(int entityId, Runnable body) {
        final Binding b = bindings.get(entityId);
        if (b == null) {
            throw new IllegalStateException("entity not registered: " + entityId);
        }
        final TaskRetirementToken token = TaskRetirementToken.create();
        synchronized (b) {
            if (b.pendingTokens.size() < b.pendingTokensCapacity) {
                b.pendingTokens.add(token);
            } else {
                // 【2026-10-23 修复：溢出 token 必须拒绝，不能"照常调度但不跟踪"】
                //
                // 原来是 `b.pendingTokensOverflow++` 然后继续 schedule，
                // 注释写着 "cleanup responsibility transferred to task itself" ——
                // 但**没有任何代码承担这个责任**：
                //   - `unregisterEntity` 只遍历 `pendingTokens`，看不到溢出 token；
                //   - 于是实体被移除后，这个 body 仍会在 region 线程上执行，
                //     去改动一个已经不存在的实体（陈旧工作）。
                //
                // 容量语义是"允许为单个实体排队的任务上限"，超了就该**拒绝**这次调度
                // （并 retire 刚建的 token），而不是把不受管的任务放出去。
                // 调用方拿到已 retire 的 token 可以自行识别本次未被受理。
                b.pendingTokensOverflow++;
                token.retire();
                return token;
            }
        }
        scheduler.schedule(b.region, body, token);
        return token;
    }

    /** Retires all pending (not completed) tasks of the entity and unbinds it. @return retired count */
    public int unregisterEntity(int entityId) {
        Binding b = bindings.remove(entityId);
        if (b == null) {
            return 0;
        }
        int retired = 0;
        synchronized (b) {
            for (TaskRetirementToken t : b.pendingTokens) {
                if (!t.isCompleted() && !t.isRetired()) {
                    t.retire();
                    retired++;
                }
            }
            b.pendingTokens.clear();
        }
        return retired;
    }

    /** Returns the overflow count for a bound entity (diagnostic only). */
    public long getEntityOverflow(int entityId) {
        Binding b = bindings.get(entityId);
        if (b == null) {
            return 0L;
        }
        synchronized (b) {
            return b.pendingTokensOverflow;
        }
    }
}

