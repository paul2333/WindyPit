# WindyPit 1.8.8 Owner Contract（Prompt 70）

- 仓库：`C:\Users\ylxyl\OneDrive\Desktop\WindyPit`
- 分支：`windypit-1.8-threaded`
- 契约基线 HEAD：`395fe4765d2757b3b567fa2eeabe358cbf12e17c`
- 判定：**PARTIAL**。本文只描述当前工作树能被源码和测试证明的 owner 边界；未证明的部分列在第 8 节，不得按“已并行”宣传。

本文是 WindyPit 线程边界的唯一契约。任何 NMS/Bukkit 访问都必须能归入第 2 节的一个域；不能归类的访问默认属于 **primary/server-control**，在拿到 owner 断言和运行时 violation detector 证据之前，禁止迁移到任何 owner 线程。

## 0. 事实基线（禁止误读）

1. `WorldTicker` 仍在 **primary（server main）线程**直接执行 `WorldServer.doTick()` 和 `WorldServer.tickEntities()`。这两个调用没有移动、没有外包、没有异步化。
2. `WorldRegionRuntime` 当前只对 **immutable tracker snapshot** 做可见性规划：owner 线程运行 `TrackerVisibilityPlanner`，产出的 plan 是纯数据。
3. `NmsTrackerPlanApplier` 仍在 **primary 线程**把 plan 的 add/remove 应用到 NMS `EntityTracker`。
4. `WorldTicker` 的 tracker 步骤仍在 primary 线程同步执行（`WorldTrackerTick.run(this)`）。
5. 因此当前能力是 **per-world tracker visibility planning**，不是世界并行、实体 tick 并行、战斗异步化或网络异步化。任何文档、报告、提交信息都不得写成“世界 tick 已并行”。

## 1. 术语

| 术语 | 定义 |

