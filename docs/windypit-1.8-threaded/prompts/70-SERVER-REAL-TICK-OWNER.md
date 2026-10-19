# Prompt 70 — WindyPit 1.8.8 真实 owner/tick 收口

仓库：`C:\Users\ylxyl\OneDrive\Desktop\WindyPit`，分支 `windypit-1.8-threaded`。前置：插件 owner API 已冻结；由 DSH 自己执行，不调用 Claude Code。

## 当前缺口

`WorldRegionRuntime` 目前对 immutable tracker snapshot 做并发可见性规划，`NmsTrackerPlanApplier` 在主线程应用 plan；`WorldTicker` 仍在主线程调用 `WorldServer.doTick()` 和 `tickEntities()`。本 prompt 的第一条验收是保留这个事实，禁止把 tracker 并行包装成完整世界并行。

## 安全实施顺序

1. 先建立 `docs/windypit-1.8-threaded/owner-contract.md`：server control、shared arena、world region、entity、connection、global task、cross-owner message、chunk load/unload、quit/transfer/shutdown 的 owner 和允许跨线程数据。每条 NMS/Bukkit 访问必须能归类。
2. 为实体/区块注册、移除、卸载、玩家退出和跨 world transfer 加 generation/owner assertion。跨 owner 只能传 immutable message；`CallerRunsPolicy`、无界队列和任意 primary fallback 全部禁止。
3. 在非竞技世界先做可证明安全的 owner-only tick seam：每个 owner 只处理自己已注册且无跨 owner 引用的维护任务；共享 arena、玩家战斗、网络发送和 Bukkit event 继续 primary/arena owner。若 NMS 仍有隐式全局表或插件 callback，保留同步路径并写出缺口，不得强行并行。
4. 若要移动 `WorldServer.doTick()` 或 `tickEntities()`，必须先通过源码审计和运行时 violation detector 证明所有访问都在 owner；加入 fail-fast 测试和 rollback flag。无法证明时不要移动这两个调用。
5. 保留 tracker plan 的 exactly-once、stale drop、full resync、低延迟 projectile 和 shutdown drain；补充多个独立世界的生产工作 ID、owner thread、start/end、结果消费证据，不能只测 barrier。
6. `WindyPitConfig` 与 `WindSpigotConfig` 对 `windypit.yml` 的所有权必须收敛：定义唯一 schema owner，未知键 round-trip，launcher manifest 与 server config 分开；所有 parsed key 要有真实决策点。

## 测试与运行

```powershell
cd C:\Users\ylxyl\OneDrive\Desktop\WindyPit
.review\harness\mvn-jdk25.bat -o -B -pl WindSpigot-Server test
.review\harness\mvn-jdk25.bat -o -B clean install
```

增加 `WorldOwnerLifecycleTest`、`EntityCrossOwnerMessageTest`、`ChunkUnloadOwnerTest`、`WorldTickRollbackTest`、`MultiWorldProductionConsumptionTest`。REGION smoke 必须显示至少两个 owner 执行不同 world 的有用工作；共享 arena 仍有序。任何只有计数、空快照或主线程 queue pump 的证据仍是 `PARTIAL`。

