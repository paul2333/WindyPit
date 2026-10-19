# WindyPit 服务端审计报告

审计对象：`C:\Users\ylxyl\OneDrive\Desktop\WindyPit`（审计时 HEAD `357c934`，修复提交 `d4d3a9a`）
审计日期：2026-10-19
审计方式：静态代码证据 + 运行期实测（恢复后 jar 实机启动、`/windypit-tps` 输出）

> **修复状态**：第 1.1 节的"12 个空转线程"已在 `d4d3a9a` 修复并实测验证（JVM 线程 33 → 20）。
> 其余结论（多线程实际并行范围、698 行零引用代码、5 个死配置键）仍然有效。

---

## 一、多线程是否真正生效

### 结论：**默认配置下不生效；REGION 模式下只有"追踪可见性规划"这一小块真正并行。**

### 1.1 COMPAT 模式（默认）

证据 `windypit.yml`：

```yaml
threading:
  mode: COMPAT
```

证据 `WindyPitTickIntegration.java:82-93`：

```java
final boolean regionMode = config.threading().mode() == WindyPitConfig.ThreadingMode.REGION
        && config.features().asyncEntityTracking()
        && !config.rollback().disableAsyncTracking()
        && !config.rollback().disableRegionScheduler();
if (regionMode) {
    this.worldRegions = new WorldRegionRuntime(...);
} else {
    this.worldRegions = null;   // ← COMPAT 走这里
}
```

因此 `worldRegions == null`，`WorldTicker.java:109-116` 的 `ownerPlans` 恒为 `false`，`applyPending` / `submitWorld` 都不会执行。

同时 `WindyPitTickIntegration.java:72-79` 仍然无条件创建 `ComputePool`：

```java
int computeThreads = config.threading().resolveThreads(..., Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
this.computePool = new ComputePool(computeThreads, 1024, regionScheduler, ...);
```

**修复前实测证据**（24 核机器）：

```
Multithreading:
  Mode: COMPAT | Effective: NOT ACTIVE (COMPAT: all work on the primary thread)
  CPUs: 24 | JVM threads: 33 | total: 1.04 cores (4%)
  Per-group CPU (1s window):
    Primary (Server thread)   0.0% | 0.00 cores | 1 threads
    Compute pool              0.0% | 0.00 cores | 12 threads
```

12 个 `WindyPit-Compute-*` 线程**已创建但 CPU 占用 0%**，属于纯资源占用。

**修复后实测证据**（提交 `d4d3a9a`，同样 COMPAT、同样 24 核）：

```
Multithreading:
  Mode: COMPAT | Effective: NOT ACTIVE (COMPAT: all work on the primary thread)
  CPUs: 24 | JVM threads: 20 | total: 0.99 cores (4%)
  Per-group CPU (1s window):
    Primary (Server thread)   0.0% | 0.00 cores | 1 threads
```

`Compute pool` 分组消失，JVM 线程数 33 → 20。修复方式：`ComputePool` 的 worker 线程改为
`submit()` 首次调用时才创建（`ComputePool.java` `startWorkersIfNeeded()`），未使用的池不持有任何线程。

### 1.2 REGION 模式

`WorldTicker.java:105-116` 是唯一真实并行点：

```java
final boolean ownerPlans = worldRegions != null && worldRegions.isRegistered(worldName);
if (ownerPlans) {
    worldRegions.applyPending(worldName);   // 主线程：应用上一 tick 的规划
}
WorldTrackerTick.run(this);                 // 主线程：tracker 步进
if (ownerPlans) {
    worldRegions.submitWorld(worldserver);  // 交给 owner 线程：构建下一 tick 规划
}
```

**真正跑在 `WindyPit-Region-*` 线程上的只有**：`TrackerVisibilityPlanner.plan(...)` —— 即"谁应该看见谁"的可见性规划计算（`WorldRegionRuntime` → `PlanFunction`）。

**REGION 模式实测**（`threading.mode=REGION`、`region-threads: 4`、无玩家）：

```
Loaded windypit.yml: threading.mode=REGION, arena-owner=PRIMARY, region-threads=4
World owner domains: 3 | applied=0 dropped=0 rejected=0 failed=0
  World world         [world-owner-0]: submitted=0 planned=0 applied=0 ... owner=WindyPit-Region-world-owner-0
  World world_nether  [world-owner-1]: submitted=0 planned=0 applied=0 ... owner=WindyPit-Region-world-owner-1
  World world_the_end [world-owner-2]: submitted=0 planned=0 applied=0 ... owner=WindyPit-Region-world-owner-2

Multithreading:
  Mode: REGION | Effective: ACTIVE (3 owner domains on dedicated threads)
  CPUs: 24 | JVM threads: 23 | total: 0.93 cores (4%)
  Per-group CPU (1s window):
    Primary (Server thread)   0.0% | 0.00 cores | 1 threads
    Region owner             0.0% | 0.00 cores | 3 threads
```

**关键发现**：3 个 owner 线程已创建且绑定到 3 个世界，但 `submitted=0 planned=0 applied=0`，
CPU 占用 0%。原因是 `WorldRegionRuntime.java:326-331`：

```java
if (world.players.isEmpty()) {
    // No viewers: stop planning and force a full visibility resync when a player joins
    state.previous = null;
    return;
}
```

无人服时 `submitWorld` 直接返回，**永远不会向 owner 线程提交规划任务**。所以"REGION 模式已生效"
在无人服场景下只意味着"线程建好了"，不代表"有并行工作在跑"。

**仍在主线程串行执行的**：

| 工作 | 证据 |
|---|---|
| 世界 tick（方块/红石/天气/村庄） | `WorldTicker.java:70` `worldserver.doTick()` |
| 实体 tick | `WorldTicker.java:86` `worldserver.tickEntities()` |
| tracker 步进与发包 | `WorldTicker.java:113,139` `WorldTrackerTick.run` → `getTracker().updatePlayers()` |
| 规划应用（写 NMS 状态） | `WorldTicker.java:111` `applyPending` |
| 所有世界循环 | `WorldTickManager.java:31-34` 单线程 `for` 循环 |

### 1.3 ComputePool 是死代码

全仓 `ComputePool.submit` 仅 2 处调用：

- `BotDecisionDispatcher.java:28`
- `TrackerAsyncEngine.java:33`

而这两个类在生产代码中**零实例化**：

```
BotDecisionDispatcher  refs=0  in=[]
TrackerAsyncEngine     refs=1  in=[TrackerShadowFeed.java]  ← 仅注释提及
```

`TrackerShadowFeed.java:78` 原文：

```java
// TrackerAsyncEngine integration for actual planning goes here in a later revision
```

**结论：`ComputePool` 的 12 个线程在生产路径上永远收不到任务。** `/windypit-tps` 里 `submitted=0 executed=0` 不是"没负载"，是"没有调用者"。

### 1.4 TickPipeline 计数永远为 0

`TickPipeline.tick()` **全仓零调用**。唯一调用的是 `runSegment`：

```
WindyPitTickIntegration.java:189  pipeline.runSegment(REGION_SNAPSHOT, OWNER_COMMIT);
WindyPitTickIntegration.java:198  pipeline.runSegment(SHUTDOWN_DRAIN, SHUTDOWN_DRAIN);
```

`runSegment` 里 `segmentCount++`（`TickPipeline.java:116`），但 `tick()` 里才 `tickCount++`（`TickPipeline.java:98`）。

因此 `/windypit-tps` 显示的 `Pipeline ticks` 恒为 0，这是设计使然，不是 bug，但会误导运维。

### 1.5 多线程生效度评分

**2 / 10**

理由：默认配置零并行；REGION 模式下也仅把"可见性规划"这一项（占 tick 成本很小）移出主线程，而世界 tick、实体 tick、tracker 发包、状态应用全部仍在主线程串行。

---

## 二、极致精简是否做到

### 结论：**没有。至少 698 行生产代码零引用，另有 5 个配置键解析后无人读取。**

### 2.1 零引用类（生产代码 0 引用，仅测试或完全无引用）

| 类 | LOC | 生产引用 | 测试引用 |
|---|---:|---:|---:|
| `NetworkSendQueue` | 156 | 0 | 15 |
| `WindyPitDiagnostics` | 136 | 0 | 10 |
| `EntityOwnershipService` | 98 | 0 | 6 |
| `ThreadViolationReporter` | 79 | 0 | 0 |
| `DurableOperationLog` | 55 | 0 | 0 |
| `ThreadContext` | 45 | 0 | 0 |
| `BotBudget` | 43 | 0 | 0 |
| `DurableStateRecovery` | 37 | 0 | 0 |
| `TaskRetirementToken` | 25 | 0 | 0 |
| `BotDecisionDispatcher` | 24 | 0 | 0 |
| **合计** | **698** | | |

验证方式：对每个类名在全仓 `Select-String`，排除自身文件与 `src/test`。

### 2.2 零引用配置键

`WindyPitConfig.java:251-262` 解析了以下键，但没有任何运行时代码读取：

```java
String entityThreads      = yaml.getString("threading.entity-threads", "auto");      // 仅存入 ThreadingConfig，无消费方
String persistenceThreads = yaml.getString("threading.persistence-threads", "bounded"); // 同上
yaml.getBoolean("features.async-visual-projection", true),   // FeaturesConfig 字段无读取方
yaml.getBoolean("features.async-pathfinding", true),         // 同上
yaml.getBoolean("features.async-persistence", true),         // 同上
```

`features.async-pathfinding` 尤其误导：它看起来会开启异步寻路，但仓库中不存在任何异步寻路实现。

### 2.3 重复实现

| 重复项 | 证据 |
|---|---|
| 两套可见性规划路径 | `TrackerAsyncEngine`（死）+ `WorldRegionRuntime`（活），都调 `TrackerVisibilityPlanner` |
| 两套诊断出口 | `WindyPitDiagnostics`（死，136 行）+ `SchedulerDiagnostics`（活） |
| 两套队列抽象 | `NetworkSendQueue`（死，156 行）+ `RegionScheduler` 内置有界队列 |

### 2.4 死分支

`WindyPitTickIntegration.java:117`：

```java
if (arena.mode() == ExecutionMode.PUMPED_PRIMARY) {
    regionScheduler.pumpOnce(arena);
}
```

默认 `arena-owner: PRIMARY`（`windypit.yml`）确实走这里，但 `pipeline.runSegment(REGION_SNAPSHOT, OWNER_COMMIT)` 的相位范围是否包含 `OWNER_COMMIT` 决定了这段是否执行 —— 实测 `region global: scheduled=0` 说明 `pumpOnce` 没有实际任务可泵。

### 2.5 精简度评分

**4 / 10**

理由：核心路径（`RegionScheduler`/`WorldRegionRuntime`/`WindyPitTickIntegration`）结构清晰，但外围堆积了 698 行零引用代码和 5 个死配置键，且死代码有对应测试"证明其存在"，形成虚假覆盖率。

---

## 三、可安全删除清单（按 LOC 降序）

| 类/项 | LOC | 删除风险 | 说明 |
|---|---:|---|---|
| `NetworkSendQueue` | 156 | 低 | 零生产引用；仅 `NetworkSendQueueTest` 引用，删类需同步删测试 |
| `WindyPitDiagnostics` | 136 | 低 | 零生产引用；`/windypit-tps` 用的是 `SchedulerDiagnostics` |
| `EntityOwnershipService` | 98 | 中 | 零生产引用，但 `EntityScheduler` 依赖它；需先确认 `EntityScheduler` 是否也需要删 |
| `ThreadViolationReporter` | 79 | 低 | 完全零引用（含测试） |
| `DurableOperationLog` | 55 | 低 | 仅 `DurableStateRecovery` 引用，而后者也零引用 |
| `ThreadContext` | 45 | 低 | 完全零引用 |
| `BotBudget` | 43 | 低 | 完全零引用 |
| `DurableStateRecovery` | 37 | 低 | 完全零引用 |
| `TaskRetirementToken` | 25 | 低 | 零生产引用，但 `RegionScheduler` API 暴露了它，删除需改签名 |
| `BotDecisionDispatcher` | 24 | 低 | 完全零引用 |
| 配置键 `entity-threads` | — | 低 | 解析后无消费方 |
| 配置键 `persistence-threads` | — | 低 | 解析后无消费方 |
| 配置键 `async-visual-projection` | — | 低 | 字段无读取方 |
| 配置键 `async-pathfinding` | — | 低 | 无实现 |
| 配置键 `async-persistence` | — | 低 | 无实现 |

**不建议删除**：`ComputePool`（230 行）虽当前无调用者，但它是 REGION 模式规划器的既定执行器，删除会破坏 `WorldRegionRuntime` 的设计前提。正确做法是**接上调用方**或**在 COMPAT 下不创建线程池**。

---

## 四、优先修复建议（按 ROI 排序）

1. **COMPAT 模式不创建 ComputePool**（`WindyPitTickIntegration.java:72-79`）—— 省 12 个空转线程，改动 3 行。
2. **`/windypit-tps` 明确标注 tickCount 恒 0 的原因**，避免运维误判。
3. **删除 698 行零引用代码 + 5 个死配置键** —— 直接提升精简度评分。
4. **若要真正多线程**：把 `worldserver.doTick()` / `tickEntities()` 按世界拆分到 owner 线程，而不是只拆可见性规划。这是架构级改动，需要先解决 NMS 跨线程写冲突。

---

## 五、审计方法可复现命令

```powershell
# 零引用类
$root='C:\Users\ylxyl\OneDrive\Desktop\WindyPit\WindSpigot-Server\src\main\java'
Get-ChildItem $root -Recurse -Filter *.java | Select-String -Pattern '\bNetworkSendQueue\b'

# ComputePool 调用者
Get-ChildItem $root -Recurse -Filter *.java | Select-String -Pattern '\.submit\('

# 运行期实测
java -jar WindSpigot.jar nogui   # 然后控制台输入 windypit-tps
```
