# WindyPit / ThePitUltimate 下一轮剩余工作计划

本文件记录当前验收状态和下一轮范围。具体任务、实现边界和命令以 `prompts/45-NEXT-ROUND-MASTER.md` 及编号专项 prompt 为准。所有执行都由 DSH 直接负责；不得调用 Claude Code。

## 固定目标和设计

- 服务端为 Java 25 + WindSpigot/Bukkit/NMS 1.8.8；协议 47 客户端兼容是硬目标。
- Folia 只作为 owner-thread / region scheduler 的设计参考；不迁移到现代 Folia/Paper，也不引入现代物品/Adventure API 到服务端核心。
- ThePit 共享竞技场是单一、有序 owner。PvP、Bukkit/NMS 事件和实体/背包/世界修改不得整体异步化。
- 只将不可变快照交给固定、有界、可监控 worker；结果带 generation/sequence 后返回正确 owner。
- 新插件 WindyPit 集成代码放在 `com.windypit.pit.*`。旧 `net.mizukilab.*` 仅作为兼容桥，不再新增集成实现。
- 任何并行功能必须有队列容量、拒绝/过期可见性、关闭 drain、owner assertion 和单独 rollback flag。
- 禁止 `git reset`、`git clean`、强制 checkout、宽泛暂存、amend、覆盖用户文件和未经授权 push。

## 当前已核实状态（2026-10-18）

仓库：

- WindyPit：`C:\Users\ylxyl\OneDrive\Desktop\WindyPit`，分支 `windypit-1.8-threaded`，HEAD `395fe4765d2757b3b567fa2eeabe358cbf12e17c`。
- ThePitUltimate：`C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release`，分支 `thepitultimate-1.8-threaded`，HEAD `729e23948cd4238d5f8ce2166236cc10f04fe59d`。
- 两个工作树都存在未提交/未跟踪文件，启动下一轮时必须逐路径盘点并保留；不得把干净分支误报为当前状态。

本轮执行记录和产物 hash 见 `ACCEPTANCE-20261018.md`。当前工作树本轮命令结果：

- WindyPit Maven 3.9.16 / Microsoft JDK 25.0.3：`-pl WindSpigot-Server test` 和 `clean install` 都退出 0；测试 180/0。
- ThePitUltimate Gradle 9.8.0 / Microsoft JDK 25.0.3：` :base:test --rerun-tasks`、`:base:check`、`:base:build --rerun-tasks` 均退出 0；测试 1559/0。
- 两个生成 jar 通过 `artifact-boundary-scan.ps1`；WindyPit core 未新增 ProtocolLib、现代 Paper/Folia/DataComponent API。

## 验收状态矩阵

| 工作区 | 状态 | 已经证实 | 未通过/未证明 |
|---|---|---|---|
| WindyPit build/tests | PASS | Java 25 clean install，180/0 | 当前提交的独立 clean checkout 仍需下一轮复验 |
| WindyPit owner runtime | PARTIAL | 每世界 owner 线程运行不可变 tracker visibility plan，主线程应用 plan，队列和 drain 有测试 | `WorldTicker` 仍在主线程跑 `WorldServer.doTick()`/`tickEntities()`；不能称完整 NMS 世界/实体并行 |
| ThePit movement/auth | PASS（内部） | 默认测试覆盖、真实 session lifecycle 的源码/测试证据 | 需要真实客户端 session 回归 |
| ThePit combat plan | NOT_STARTED/PARTIAL | plan/calculator 类和单测 | 没有完整生产 consumer；伤害/击退/死亡/奖励 parity 未 runtime 验证 |
| ThePit bot/NPC | PARTIAL | legacy manager/NPC 功能在插件内；owner adapter 组件存在 | WindyPit entity-owner 路由没有完整生产 caller/生命周期证据 |
| Visual/timers | PARTIAL | nametag detector 与单个 timer 有接线 | scoreboard/actionbar/bossbar/hologram 和其余 timer 未全部接线；停服日志有 `Not scheduled yet` |
| Commerce durability | PARTIAL | market buy durable ledger + 两个子进程硬停点 | trade/mail/reward/membership/booster 未完整接入；没有多 JVM claim、retention/pruning |
| Protocol/dependency | PARTIAL | 裸服务端 protocol-47 probe 登录/移动/背包点击通过；插件 enable/kernel upgrade/stop 通过 | ThePit-enabled probe 在 VarInt 阶段断开；Adventure 下载 404；WorldEdit 缺类导致事件加载错误 |
| Live performance | NOT_STARTED | TPS 展示、诊断实现 | 无 0/20/50/100 PvP 压力、无 combat JFR、无 50ms+ 栈分类 |

总体状态为 **PARTIAL / NOT RELEASE_READY**。见 `ACCEPTANCE-20261018.md` 与当前 `.review` 原始记录。裸服务端 protocol-47 通过不等于 ThePit gameplay parity；market buy 恢复不等于完整 commerce 恢复。

## 下一轮有序实施

1. **战斗 owner 接线** — `prompts/50-PLUGIN-COMBAT-OWNER.md`。把 immutable combat snapshot/plan 接到实际 `CombatListener`、`GameEffectListener`、投射物和死亡奖励路径；保留单 arena owner 顺序，加入 legacy fixtures、generation guard 和 rollback。
2. **完整 commerce durable ledger** — `prompts/55-PLUGIN-COMMERCE-LEDGER.md`。将 trade/mail/rewards/membership/boosters 接入 SQLite single-writer/coordinator；做 RESERVED、commit 前后、owner apply、中止/重试、多 JVM 的真子进程硬停恢复。
3. **bot/NPC/visual/task lifecycle** — `prompts/60-PLUGIN-BOT-NPC-VISUAL.md`。把 bot/NPC 实体动作路由到 entity owner；将 nametag/scoreboard/actionbar/bossbar/hologram 的变化检测接入 caller；修复重复取消/未 schedule 和 session 清理。
4. **依赖和真实协议** — `prompts/65-DEPENDENCY-PROTOCOL47-LIVE.md`。修复依赖解析与 WorldEdit capability；debug ThePit-enabled VarInt close；真实 1.8.8 client 做登录到 market/GUI/战斗闭环。
5. **服务端 owner/tick 安全收口** — `prompts/70-SERVER-REAL-TICK-OWNER.md`。建立 NMS/Bukkit owner contract、实体/区块迁移生命周期、共享 arena owner assertion，合并 `WindyPitConfig`/`WindSpigotConfig` 对 `windypit.yml` 的双所有权。只有证明线程安全后才将非竞技 world tick 移出主线程；不能证明时保留同步路径并如实标 PARTIAL。
6. **性能和发布门禁** — `prompts/80-PERF-JFR-RELEASE.md`。同一环境跑 0/20/50/100 和混合负载，JFR 分类 50ms+ 栈，重跑 full parity、crash、rollback、clean checkout、边界扫描和停服 drain。

执行总控用 `prompts/45-NEXT-ROUND-MASTER.md`。更早 prompts (`05` 到 `40`) 是历史阶段合同，只能在最新 master 明确要求时参考；如果与当前验收相矛盾，以当前报告和最新专项 prompt 为准，不得照搬旧 HEAD、旧失败和旧状态。

## 发布完成条件

- 两个提交在隔离 clean checkout 上用 Microsoft JDK 25 重建；测试和边界扫描通过。
- 每个 feature matrix 行都有生产调用点、owner、生命周期、行为 fixture、rollback flag 和匹配的 runtime 证据。
- ThePit 的真实 protocol-47 gameplay 闭环通过；外部缺客户端时仅该项可以 BLOCKED_EXTERNAL，依赖 404 等内部配置问题先修。
- 全部交易类型在 child JVM 硬停后真实恢复且恰好一次；停服 drain 无已接受事务被丢弃。
- WindyPit 的实际多线程收益明确来自什么工作；不得把 tracker planning、单测 barrier 或 worker thread 存在误报为玩家 PvP 或完整 world tick 已并行。
- 完成固定 0/20/50/100 压测、baseline 对比和 combat JFR；平均 TPS >= 19.5、tick p99 <= 50ms，无未解释核心 owner 50ms+ 重复调用栈、无已接受工作丢失。

任意条件未过，最终结果保持 `PARTIAL` 或 `BLOCKED_EXTERNAL`，不得使用 `RELEASE_READY`。

