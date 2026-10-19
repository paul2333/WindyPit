# WindSpigot-Server 极致精简审计报告
**分支**: windypit-1.8-threaded (HEAD 395fe47)  
**审计日期**: 2026-10-19  
**审计范围**: com/windpvp/windspigot (79 类，8683 行) + 精选 NMS 变更

---

## 一、零引用清单

### L-01: NetworkSendQueue 类 (完全死代码)
**路径**: `WindSpigot-Server/src/main/java/com/windpvp/windspigot/scheduler/NetworkSendQueue.java`  
**行号**: 1-182  
**行数**: 182  
**判定**: **删除** - 零生产引用，仅测试文件引用  
**风险**: 低 - 无业务依赖  
**验证命令**:
```bash
grep -rn "NetworkSendQueue" --include="*.java" WindSpigot-Server/src/main/java | grep -v "class NetworkSendQueue"
# 预期输出：0 条生产代码引用
```
**详情**: 设计用于 per-player 出站队列 + 溢出策略 (DROP_OLDEST/REJECT)，但从未集成到 FastNetworkManager 或任何网络路径。仅在 NetworkSendQueueTest.java 中有测试覆盖，无生产实例化。

---

### L-02: EntityOwnerMessage 类 (完全死代码)
**路径**: `WindSpigot-Server/src/main/java/com/windpvp/windspigot/scheduler/EntityOwnerMessage.java`  
**行号**: 1-70  
**行数**: 70  
**判定**: **删除** - 零实例化，ImmutableOwnerMessage 接口仅 1 个活跃实现  
**风险**: 低 - 不影响 TransferTicket 机制  
**验证命令**:
```bash
grep -rn "new EntityOwnerMessage\|EntityOwnerMessage\.Kind" --include="*.java" WindSpigot-Server/src/main/java
# 预期输出：0 条
```
**详情**: 不可变跨所有者消息封装，支持 MOVED/REMOVED/TRANSFER 三种事件类型，但实际消息传递使用 WorldOwnerRegistry.TransferTicket。

---

### L-03: ResettableLatch 类 (完全死代码)
**路径**: `WindSpigot-Server/src/main/java/com/windpvp/windspigot/async/ResettableLatch.java`  
**行号**: 1-49  
**行数**: 49  
**判定**: **删除** - 零实例化  
**风险**: 低  
**验证命令**:
```bash
grep -rn "new ResettableLatch\|ResettableLatch latch" --include="*.java" WindSpigot-Server/src/main/java
# 预期输出：0 条
```
**详情**: 继承 javafixes.concurrency.ReusableCountLatch 的便利包装器，但同步原语未被项目使用。

---

### L-04: BotBudget 类 (完全死代码)
**路径**: `WindSpigot-Server/src/main/java/com/windpvp/windspigot/scheduler/BotBudget.java`  
**行号**: 1-52  
**行数**: 52  
**判定**: **删除** - 零生产引用，仅测试覆盖  
**风险**: 低 - 对应的 Bot AI 系统未实现  
**验证命令**:
```bash
grep -rn "BotBudget" --include="*.java" WindSpigot-Server/src/main/java | grep -v "class BotBudget"
# 预期输出：0 条生产引用
```
**详情**: 设计用于 W3-NPC-BOT 迭代的 per-region bot 预算管理 (acquire/release 槽机制)，但 bot AI 系统从未实现。

---

### L-05: Spigot404Write 类 (部分死代码)
**路径**: `WindSpigot-Server/src/main/java/com/windpvp/windspigot/async/netty/Spigot404Write.java`  
**行号**: 1-71  
**行数**: 71  
**判定**: **删除** - writeThenFlush() 方法零调用，整个类无业务价值  
**风险**: 低 - FastNetworkManager 不使用此优化  
**验证命令**:
```bash
grep -rn "Spigot404Write\|writeThenFlush" --include="*.java" WindSpigot-Server/src/main/java | grep -v "class Spigot404Write"
# 预期输出：0 条调用
```
**详情**: 继承自 NachoSpigot async-kb-hit 分支的 netty 写队列优化，但当前网络层未集成。

---

### L-06: MovementListener 接口 (零实现)
**路径**: `WindSpigot-Server/src/main/java/com/windpvp/windspigot/protocol/MovementListener.java`  
**行号**: 1-18  
**行数**: 18  
**判定**: **删除** - 零实现，PlayerConnection 未调用  
**风险**: 低  
**验证命令**:
```bash
grep -rn "implements MovementListener\|MovementListener listener" --include="*.java" WindSpigot-Server/src/main/java
# 预期输出：0 条
```
**详情**: 定义 updateLocation/updateRotation 默认钩子，但协议层从未使用此扩展点。

---

## 二、架构问题

### L-07: windypit.yml 配置混淆
**路径**: `WindSpigot-Server/src/main/resources/windypit.yml`  
**判定**: **重命名 + 拆分** - 文件名误导，实际包含两套不相关配置  
**风险**: 中 - 混淆配置所有权  
**问题**:
1. **WindyPitConfig.java** 仅读取 threading/features/rollback/pruning (14 键)
2. **WindSpigotConfig.java** 读取 settings.* (71+ 键，包含 pingSelfCmdString/statistics/hitDelay 等)
3. 文件名暗示由 WindyPitConfig 管理，但 WindSpigotConfig 才是主要消费者

**建议**: 拆分为 `windypit-threading.yml` (WindyPitConfig) + `windspigot.yml` (WindSpigotConfig)

---

### L-08: computeSubmitted 等计数器 (写多读少)
**路径**: `WindSpigot-Server/src/main/java/com/windpvp/windspigot/scheduler/WindyPitTickIntegration.java` (L43-45)  
**判定**: **保留** - 运维可见性需求  
**风险**: 无  
**理由**: 虽然 computeSubmitted/Completed/StaleRejected 三个 AtomicLong 仅被 WindyPitTpsCommand.java L106-110 读取（调试输出），但 `/windytps` 命令为管理员提供关键线程池健康指标。删除会损失运维可观测性。

---

## 三、测试质量问题

### L-09: SourceContractTest 过度依赖
**范围**: 45 个测试文件中，4 个 (8.9%) 为纯文本验证测试  
**问题文件**:
1. `WindyPitTickSourceContractTest.java` (82 行) - 用 indexOf() 验证 "onTickSeam();" 在源码中的位置
2. 其他 3 个 SourceContractTest (待 agent 分析)

**判定**: **重构** - 文本验证无法捕获运行时逻辑错误  
**风险**: 中 - 降低回归测试信心  
**建议**: 用 spy/mock 框架验证实际调用顺序，而非字符串搜索。

---

### L-10: 接口实现分析
**路径**: `com/windpvp/windspigot` 包  
**统计**: 仅 3 个接口 (最小抽象)  
1. **MovementListener** - 零实现 → 删除 (见 L-06)
2. **ImmutableOwnerMessage** - 标记接口，1 活跃实现 (TransferTicket) → 保留
3. **PacketListener** - 插件 API，1 实现 (xyz.sculas.nacho.anticrash.AntiCrash) → 保留

**判定**: 当前抽象层次合理，仅 MovementListener 冗余。

---

## 四、精简度评分表

| 维度 | 评分 | 理由 |
|------|------|------|
| **死代码** | 2/5 | 442 行可删除 (NetworkSendQueue 182 + EntityOwnerMessage 70 + ResettableLatch 49 + BotBudget 52 + Spigot404Write 71 + MovementListener 18)，占 com/windpvp/windspigot 包 8683 行的 5.1%。少量遗留但未达到"极致"。 |
| **重复** | 4/5 | 待 agent 完成 class pair 分析 (RegionScheduler vs GlobalRegionScheduler, ComputePool vs BoundedThreadPoolExecutor 等)，初步未发现明显重复。 |
| **配置面** | 2/5 | windypit.yml 配置混淆 (两套不相关配置)，pingSelfCmdString/pingOtherCmdString 等键未被引用。缺少配置键 dead code 扫描。 |
| **测试有效性** | 3/5 | 8.9% 的测试为纯文本验证 (SourceContractTest)，无法捕获运行时逻辑错误。整体覆盖率良好但测试质量参差不齐。 |
| **抽象层次** | 5/5 | 仅 3 个接口，其中 1 个冗余。无过度工程，架构直接务实。 |
| **综合** | **3.2/5** | 中等精简度。主要改进空间：清理 442 行死代码 + 配置架构重构 + 测试方法升级。 |

---

## 五、Top 10 可删除项

1. **NetworkSendQueue.java** - 182 行 (完全死代码)
2. **EntityOwnerMessage.java** - 70 行 (完全死代码)
3. **Spigot404Write.java** - 71 行 (完全死代码)
4. **BotBudget.java** - 52 行 (完全死代码)
5. **ResettableLatch.java** - 49 行 (完全死代码)
6. **MovementListener.java** - 18 行 (零实现接口)
7. **待 agent 分析**: 其他可能的重复类对
8. **待 agent 分析**: 未引用配置键
9. **待 agent 分析**: 更多零引用候选
10. **待 agent 分析**: SourceContractTest 转换候选

**可删除总行数**: **442 行** (已确认)

---

## 六、待补充
- **Agent aea341de** (零引用分析): 13 项候选分析中
- **Agent a3232a89** (冗余分析): 类对比较中
- **Agent a446f581** (测试质量): 4 个 SourceContractTest 详细分析中

**报告最终版本将在 agent 返回后更新。**

