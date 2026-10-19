# WindyPit 1.8.8 + ThePitUltimate DSH 执行 Prompt 包

本目录供 DSH 直接执行。DSH 是唯一总控，可使用 DSH 原生子代理；禁止调用 Claude Code 或 Claude CLI。目标固定为 Java 25、WindSpigot/Bukkit/NMS 1.8.8、协议 47。Folia 只作 owner-thread 设计参考，不把运行时切换到 Folia 26.x。

## 仓库

| 仓库 | 绝对路径 | 分支 |
|---|---|---|
| WindyPit 服务端 | `C:\Users\ylxyl\OneDrive\Desktop\WindyPit` | `windypit-1.8-threaded` |
| ThePitUltimate 插件 | `C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release` | `thepitultimate-1.8-threaded` |

共享 ThePit 竞技场保持一个有序 owner；只有 immutable snapshot 和纯计算离开 owner。新 WindyPit 插件集成使用 `com.windypit.pit.*`，旧 `net.mizukilab.*` 仅保留 bridge。

## 本轮验收

当前复核记录在 [ACCEPTANCE-20261018.md](ACCEPTANCE-20261018.md)。本轮重新执行：

- WindyPit `mvn-jdk25.bat -o -B -pl WindSpigot-Server test`：180 tests，0 failures。
- WindyPit `mvn-jdk25.bat -o -B clean install`：BUILD SUCCESS。
- ThePitUltimate `:base:test --rerun-tasks`：1559 tests，0 failures。
- ThePitUltimate `:base:check` 和 `:base:build --rerun-tasks`：BUILD SUCCESS。

这些是当前工作树的内部证据，不等于完整玩法、真实客户端或性能发布通过。当前结论仍为 `PARTIAL`：世界/实体 tick 仍主线程；combat plan、bot/NPC owner、market 之外的 commerce ledger 未全部接线；ThePit 真实协议 47 gameplay、依赖闭环、0/20/50/100 压测和 combat JFR 未完成。

## 原有阶段 prompts

1. `prompts/00-MASTER-ORCHESTRATOR.md`：上一轮总控规范。
2. `prompts/05-REPOSITORY-RECONCILIATION.md`：仓库与测试基线。
3. `prompts/10-SERVER-REGION-CORE.md`：服务端 owner/tracker 核心。
4. `prompts/15-SERVER-PRUNING-DIAGNOSTICS.md`：NMS 裁剪和诊断。
5. `prompts/20-PLUGIN-MOVEMENT-AUTH-TESTS.md`：移动、认证、测试门禁。
6. `prompts/25-PLUGIN-PRODUCTION-INTEGRATION.md`：插件生产接线总审计。
7. `prompts/30-PLUGIN-DURABLE-RECOVERY.md`：market ledger 和恢复基础。
8. `prompts/40-CROSS-REPO-ACCEPTANCE.md`：上一轮双仓库验收。

## 下一轮直接执行顺序

从 `prompts/45-NEXT-ROUND-MASTER.md` 开始，然后按顺序执行：

1. `50-PLUGIN-COMBAT-OWNER.md`
2. `55-PLUGIN-COMMERCE-LEDGER.md`
3. `60-PLUGIN-BOT-NPC-VISUAL.md`
4. `65-DEPENDENCY-PROTOCOL47-LIVE.md`
5. `70-SERVER-REAL-TICK-OWNER.md`
6. `80-PERF-JFR-RELEASE.md`

每个 prompt 都包含绝对仓库路径、生产文件范围、禁止的捷径、测试命令和状态门槛。DSH 必须为每轮创建新的双方 `.review\\<round-id>` 目录，不能覆盖历史 `20261018-0030` 证据。任何必需项未通过都不得写 `RELEASE_READY`。

