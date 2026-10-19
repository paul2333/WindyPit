# Prompt 10 — WindSpigot 1.8.8 真实 owner 与 tick 并发接入

仓库：C:\Users\ylxyl\OneDrive\Desktop\WindyPit
分支：windypit-1.8-threaded
前置：Prompt 05 已盘点当前修改并记录基线。执行者为 DSH；不得调用 Claude Code。

## 目标

将已存在的 WindyPit scheduler/compute 构件接入 WindSpigot 1.8.8 的真实生产执行路径，证明独立 owner domain 并发处理有用工作，同时保持 ThePit 单共享竞技场的战斗顺序和 1.8.8 Bukkit/NMS 行为。

## 本轮必须先核实的源码事实

检查 DedicatedServer 启动与配置、MinecraftServer.tick、WorldTickManager.tickWorlds、WorldTicker.run、WorldServer.doTick/tickEntities、EntityTracker 更新、网络连接发送和 shutdown。撰写 prompt 时观察到：

- MinecraftServer 创建/调用 WindyPitTickIntegration。
- WindyPitTickIntegration 的 RegionSnapshot 只保留 entityCount/chunkCount。
- 当前 ComputeJob 只对计数求和，消费者只增加 computeCompleted 统计值。
- WorldTickManager 仍按 worlds 列表顺序调用每个 world.ticker.run。
- WorldTicker.run 仍直接执行 doTick、tickEntities、tracker 更新。
- 因此上述后台求和和 scheduler 并发测试不证明生产 world/entity tick 并行。

逐条基于当前 HEAD 重新检查，不要复制过期行号。

## 设计与实施

1. 修改运行时契约前，先列清 1.8.8 NMS/Bukkit 共享可变状态、调用链、事件触发线程、Netty 每连接事件循环、存档写入锁和插件调度器依赖。把危险或与源码不符的旧 runtime-contract.md 假设标出并修正。
2. 冻结最小可行 owner 模型：全局控制线程、owner domain 标识/生命周期、玩家/实体/chunk 归属、跨 owner immutable message、reject/stale/close/drain 语义、线程数/队列边界、rollback。不得将活的 WorldServer/Entity/Player/Inventory/Connection 引用交给纯计算线程。
3. 要求至少两种可用运行配置：COMPAT（旧主线程行为）与 REGION（只有已安全接入的 owner 工作才启动）。在 Region 模式尚未通过 NMS 集成测试前，保持兼容模式默认，不要宣称 threaded ready。
4. 先接入范围受控的真实 owner 工作，再扩大到 entity/chunk/world。每个生产 tick 一次、异常时计时正确关闭、跨区 work 有明确因果顺序。不可安全并行的全球调度、Bukkit 事件和连接修改保留在全局 owner。
5. 若用全世界对象上的单一锁包装或对活世界做并行遍历来制造线程活动，拒绝此方案。若只能并行无状态快照计算，准确报告收益边界，并继续寻找安全的独立工作单元。
6. 对每个 owner worker 用固定大小和有界 FIFO 队列；队列满时拒绝并计数，禁止 CallerRuns 将任务错误地跑在提交线程。接受的持久化/游戏命令不得静默丢弃。线程异常必须进入可见故障状态并保留可诊断上下文。
7. 实现实体/玩家绑定、generation、跨 owner handoff、卸载和服务器关闭。禁止在不同 owner 间双 tick、丢实体、复制实体、过期结果写回或从错线程操作 Bukkit/NMS。
8. 将真实生产动作接到 diagnostics：owner thread 名、tick 数/耗时、任务入队/完成/拒绝、stale 结果、队列深度/高水位、跨 owner 消息数、shutdown drain 结果。指标必须来自运行时调用点。
9. 实际服务器停止流程先停止接受任务，再 drain 已接受的工作，显式记录未完成工作。验证重复 stop、异常、interruption、timeout。确认数据库/文件 I/O 的 flush 顺序与 Netty 关闭顺序。
10. 保留一键回滚到兼容模式。回滚后的重启/重新初始化不得遗留线程、owner token 或重复 scheduler。

## 测试与证据

新增或扩展测试，测试对象必须是生产调用链而不只是 scheduler class：

- 两个独立 owner domain 在 WindyPit 固定命名线程上执行真实生产任务，计时重叠且有结果被消费。
- 同一 owner 的任务 FIFO、无并发；单竞技场关键顺序不变。
- 实际 WorldTickManager/WorldTicker 接线每 tick exactly-once，并能证明线程 owner；跨边界时无两处同时 tick。
- 错线程 Bukkit/NMS 访问被拒绝或可见记录；拒绝/队列满/stale/任务异常/停服 drain 都可测试。
- COMPAT 与 REGION 加载实际配置并走不同生产分支。
- NMS 必须保持主线程的路径明确列入清单；不能用 mock scheduler overlap 作为运行时证明。

命令：
- .review\harness\mvn-jdk25.bat -o -B -pl WindSpigot-Server test
- .review\harness\mvn-jdk25.bat -o -B clean install
- 实际启动新构建 WindSpigot 1.8.8 并记录启动日志与 /windypit-tps 输出；若本机不能启动 target server，记录 BLOCKED_EXTERNAL，但仍完成内部接线与测试。

记录每个命令的 exit code、测试计数、log SHA-256、构建 jar SHA-256、线程名/重叠时间和未解决 race。只有生产调用链有用并发被证明才可标记 PASS。

