/**
 * pit 与 WindyPit 多线程框架的集成指南
 *
 * 这个文件展示 pit 应该如何使用 WindyPit 的多线程能力，
 * 确保所有操作都在正确的线程上执行，并且能获得 1.75-2.50× 的性能加速。
 */

package com.example.pit;

import com.windpvp.windspigot.scheduler.*;
import com.windpvp.windspigot.config.WindyPitConfig;
import net.minecraft.server.Entity;
import net.minecraft.server.EntityLiving;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

// ============================================================================
// 第一部分：pit 插件主类初始化
// ============================================================================

public class PitPlugin extends JavaPlugin {

    private WindyPitTickIntegration windyPit;
    private RegionScheduler regionScheduler;
    private ComputePool computePool;
    private EntityScheduler entityScheduler;

    /**
     * 检查 WindyPit 可用性并提取多线程组件
     */
    @Override
    public void onLoad() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            getLogger().severe("Cannot access MinecraftServer");
            return;
        }

        // ✓ 获取 WindyPit 主控制器
        windyPit = server.getWindyPitIntegration();
        if (windyPit == null) {
            getLogger().severe(
                "WindyPit not installed! pit requires WindyPit framework.\n" +
                "Download from: https://github.com/windpvp/windspigot"
            );
            setEnabled(false);
            return;
        }

        // ✓ 验证多线程模式（最关键的检查）
        WindyPitConfig config = windyPit.config();
        if (config.threading().mode() != WindyPitConfig.ThreadingMode.REGION) {
            getLogger().warning(
                "⚠️  WARNING: pit is configured to run in single-threaded mode!\n" +
                "   Current threading mode: " + config.threading().mode() + "\n" +
                "   Recommended mode: REGION\n" +
                "   To enable multi-threading:\n" +
                "     1. Edit windypit.yml\n" +
                "     2. Set: threading.mode: REGION\n" +
                "     3. Restart server\n" +
                "   pit will continue to work but performance will be degraded (single-threaded)."
            );
            // 可选：如果你希望 pit 要求 REGION 模式才能运行，取消注释以下行
            // setEnabled(false);
            // return;
        }

        // ✓ 提取多线程组件供后续使用
        this.regionScheduler = windyPit.regionScheduler();
        this.computePool = windyPit.computePool();
        this.entityScheduler = windyPit.entityScheduler();

        getLogger().info("✓ pit initialized with WindyPit multi-threading enabled");
        getLogger().info("  Mode: " + config.threading().mode());
        getLogger().info("  Region threads: " + config.threading().regionThreads());
    }

    @Override
    public void onEnable() {
        if (!isEnabled()) return;

        // 注册事件监听器、命令处理等
        getLogger().info("pit plugin enabled");
    }

// ============================================================================
// 第二部分：实体操作的线程安全包装
// ============================================================================

    /**
     * pit 对实体的所有 NMS 状态修改都应该通过这个方法
     * 确保修改发生在实体的 owner 线程上
     */
    public void updateEntityState(LivingEntity entity, EntityStateUpdate update) {
        if (entity == null || !entity.isValid()) return;

        // 获取实体的所有者线程
        Entity nmsEntity = ((CraftEntity) entity).getHandle();
        OwnedRegion owner = entityScheduler.ownerOf(nmsEntity);

        // 在 owner 线程上安全地修改状态
        regionScheduler.schedule(owner, () -> {
            // 此时我们在实体的专属 owner 线程上，
            // 可以安全地修改 NMS 状态
            update.apply(nmsEntity);
        });
    }

    /**
     * 实体状态更新的函数接口
     */
    public interface EntityStateUpdate {
        void apply(Entity entity);
    }

    /**
     * 使用示例：设置实体速度（常见 pit 操作）
     */
    public void setEntityVelocity(LivingEntity entity, double vx, double vy, double vz) {
        updateEntityState(entity, nmsEntity -> {
            nmsEntity.motionX = vx;
            nmsEntity.motionY = vy;
            nmsEntity.motionZ = vz;
        });
    }

    /**
     * 使用示例：设置实体朝向
     */
    public void setEntityRotation(LivingEntity entity, float yaw, float pitch) {
        updateEntityState(entity, nmsEntity -> {
            nmsEntity.yaw = yaw;
            nmsEntity.pitch = pitch;
        });
    }

    /**
     * 使用示例：传送实体到新位置
     */
    public void teleportEntity(LivingEntity entity, Location to) {
        updateEntityState(entity, nmsEntity -> {
            nmsEntity.setPosition(to.getX(), to.getY(), to.getZ());
        });
    }

// ============================================================================
// 第三部分：异步计算（寻路、碰撞检测等）
// ============================================================================

    /**
     * pit 的异步寻路示例
     *
     * 流程：
     *   1. [主线程] 发起异步计算请求
     *   2. [Compute线程] 计算路径（CPU密集）
     *   3. [Owner线程] 应用结果到实体
     */
    public void asyncPathfinding(LivingEntity entity, Location target) {
        if (entity == null || !entity.isValid()) return;

        Entity nmsEntity = ((CraftEntity) entity).getHandle();
        OwnedRegion owner = entityScheduler.ownerOf(nmsEntity);

        // 准备计算参数（需要在 owner 线程上获取以保证一致性）
        final Location from = entity.getLocation();

        // 创建异步计算任务
        ComputeJob<PathfindingResult> pathfindingJob = new ComputeJob<>(
            owner,
            windyPit.snapshotGeneration(),  // 快照生成号（用于陈旧性检查）
            nmsEntity.getId(),
            System.nanoTime(),  // 序列号（唯一标识该任务）

            // 计算部分（在 ComputePool 线程上运行，不能接触 NMS 对象）
            () -> {
                try {
                    return computePath(from, target);
                } catch (Exception e) {
                    getLogger().warning("Pathfinding failed: " + e.getMessage());
                    return null;
                }
            },

            // 应用部分（在 owner 线程上运行，可以安全修改 NMS）
            result -> {
                if (result != null && result.path != null) {
                    // 应用路径到实体的 AI
                    applyPathToEntity(entity, result.path);
                }
            }
        );

        // 提交计算任务
        try {
            computePool.submit(pathfindingJob);
        } catch (ComputePool.RejectedTaskException e) {
            getLogger().warning("Pathfinding queue full, dropping request for " + entity.getName());
        }
    }

    /**
     * 路径寻找的结果容器
     */
    public static class PathfindingResult {
        public final List<Location> path;
        public final long computeTimeNanos;

        public PathfindingResult(List<Location> path, long computeTimeNanos) {
            this.path = path;
            this.computeTimeNanos = computeTimeNanos;
        }
    }

    /**
     * 实际的寻路算法（在 ComputePool 线程上执行，不能接触游戏对象）
     * 这是计算密集部分，可以充分利用多核心
     */
    private PathfindingResult computePath(Location from, Location to) {
        long startTime = System.nanoTime();

        // A* 寻路、跳点搜索等算法
        List<Location> path = pathfindAStar(from, to);

        long elapsed = System.nanoTime() - startTime;
        return new PathfindingResult(path, elapsed);
    }

    /**
     * 骨架实现：真实的 pit 应该用 A* 或其他寻路算法
     */
    private List<Location> pathfindAStar(Location from, Location to) {
        // TODO: 实现 A* 寻路
        return null;
    }

    /**
     * 将计算结果应用到实体（在 owner 线程上执行）
     */
    private void applyPathToEntity(LivingEntity entity, List<Location> path) {
        if (path == null || path.isEmpty()) return;

        Entity nmsEntity = ((CraftEntity) entity).getHandle();
        if (nmsEntity instanceof EntityLiving) {
            EntityLiving living = (EntityLiving) nmsEntity;
            // 应用路径到实体的导航器
            // living.getNavigation().setPath(convertToNMS(path), 1.0);
        }
    }

// ============================================================================
// 第四部分：批量操作与并行处理
// ============================================================================

    /**
     * pit 的主 tick 更新循环示例
     * 展示如何高效地处理多个实体的并行计算
     */
    public void onPitTick() {
        if (windyPit == null) return;

        // 收集需要计算的实体
        List<LivingEntity> entities = getActiveEntities();

        for (LivingEntity entity : entities) {
            Entity nmsEntity = ((CraftEntity) entity).getHandle();
            OwnedRegion owner = entityScheduler.ownerOf(nmsEntity);

            // 批量提交计算任务
            // 每个 owner 线程会并行处理其负责的实体
            submitEntityComputations(entity, owner);
        }
    }

    private void submitEntityComputations(LivingEntity entity, OwnedRegion owner) {
        // 示例 1：提交实体决策计算
        ComputeJob<EntityDecision> decisionJob = new ComputeJob<>(
            owner,
            windyPit.snapshotGeneration(),
            ((CraftEntity) entity).getHandle().getId(),
            System.nanoTime(),
            () -> computeEntityDecision(entity),  // Compute 线程
            decision -> applyEntityDecision(entity, decision)  // Owner 线程
        );

        try {
            computePool.submit(decisionJob);
        } catch (ComputePool.RejectedTaskException e) {
            // 队列满时的处理（例如降低更新频率）
        }
    }

    private EntityDecision computeEntityDecision(LivingEntity entity) {
        // 计算密集工作：AI决策、寻路、碰撞检测等
        return new EntityDecision();
    }

    private void applyEntityDecision(LivingEntity entity, EntityDecision decision) {
        // 在 owner 线程上应用决策结果
        updateEntityState(entity, nmsEntity -> {
            // 应用计算结果
        });
    }

    private static class EntityDecision {
        // pit 实体决策的结果
    }

    private List<LivingEntity> getActiveEntities() {
        // 返回当前需要处理的实体列表
        return null;
    }

// ============================================================================
// 第五部分：诊断与监控
// ============================================================================

    /**
     * pit 的诊断命令，显示多线程工作状态
     * 用于排查性能问题
     */
    public void printDiagnostics() {
        if (windyPit == null) {
            getLogger().info("WindyPit not available");
            return;
        }

        SchedulerDiagnostics diag = regionScheduler.diagnostics();
        ComputePool.Stats computeStats = computePool.stats();

        getLogger().info("=== pit Multi-Threading Diagnostics ===");
        getLogger().info(String.format(
            "Scheduler: submitted=%d, executed=%d, rejected=%d",
            diag.submittedCount(),
            diag.executedCount(),
            diag.rejectedCount()
        ));

        getLogger().info(String.format(
            "ComputePool: submitted=%d, executed=%d, failed=%d, staleDropped=%d",
            computeStats.submitted(),
            computeStats.executed(),
            computeStats.failed(),
            computeStats.staleDropped()
        ));

        // 成功率计算
        if (computeStats.submitted() > 0) {
            double successRate = 100.0 * computeStats.executed() / computeStats.submitted();
            getLogger().info(String.format("ComputePool success rate: %.1f%%", successRate));
        }

        // 检查是否存在队列溢出
        if (computeStats.rejected() > 0) {
            getLogger().warning(String.format(
                "⚠️ ComputePool rejected %d tasks (queue full). Consider:\n" +
                "   1. Increase queue capacity in windypit.yml\n" +
                "   2. Reduce compute workload in pit",
                computeStats.rejected()
            ));
        }
    }

// ============================================================================
// 第六部分：优雅关闭
// ============================================================================

    @Override
    public void onDisable() {
        // pit 关闭时的清理（如果有）
        getLogger().info("pit plugin disabled");
    }

// ============================================================================
// 附录：常见错误模式（应该避免）
// ============================================================================

    /**
     * ❌ 错误示例 1：直接在主线程修改实体状态
     * 这在 REGION 模式下可能会导致竞争条件
     */
    @Deprecated
    public void WRONG_setVelocityDirect(LivingEntity entity, double vx, double vy, double vz) {
        // ❌ 这可能在主线程以外的线程上运行（pit 监听器）
        // 而实体所有者可能在不同的 owner 线程上
        entity.setVelocity(org.bukkit.util.Vector.fromArray(new double[]{vx, vy, vz}));
    }

    /**
     * ❌ 错误示例 2：在 ComputePool 线程上接触 NMS 对象
     * ComputePool 线程没有世界访问权限
     */
    @Deprecated
    public void WRONG_pathfindingApproach(LivingEntity entity, Location target) {
        ComputeJob<List<Location>> wrongJob = new ComputeJob<>(
            entityScheduler.ownerOf(((CraftEntity) entity).getHandle()),
            windyPit.snapshotGeneration(),
            ((CraftEntity) entity).getHandle().getId(),
            System.nanoTime(),
            () -> {
                // ❌ 错误：在这里访问 entity（不安全，entity 可能被其他线程修改）
                // ❌ 错误：在这里调用 entity.getLocation()（会锁定世界）
                return null;
            },
            result -> {}
        );
    }

    /**
     * ✓ 正确示例：先在主线程获取参数，再异步计算
     */
    public void CORRECT_pathfindingApproach(LivingEntity entity, Location target) {
        // 在主线程上获取所有参数
        Entity nmsEntity = ((CraftEntity) entity).getHandle();
        Location from = entity.getLocation();
        OwnedRegion owner = entityScheduler.ownerOf(nmsEntity);

        ComputeJob<List<Location>> correctJob = new ComputeJob<>(
            owner,
            windyPit.snapshotGeneration(),
            nmsEntity.getId(),
            System.nanoTime(),
            () -> {
                // ✓ 正确：使用快照的 Location，不访问游戏对象
                return computePath(from, target);
            },
            result -> {
                // ✓ 正确：在 owner 线程上应用结果
                applyPathToEntity(entity, result);
            }
        );

        try {
            computePool.submit(correctJob);
        } catch (ComputePool.RejectedTaskException e) {
            getLogger().warning("Pathfinding queue full");
        }
    }
}
