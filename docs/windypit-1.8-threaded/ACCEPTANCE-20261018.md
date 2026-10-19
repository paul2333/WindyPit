# WindyPit / ThePitUltimate 当前验收记录（2026-10-18）

本记录只使用本轮在当前工作树执行的命令和已保存的运行证据。它不是发布许可；最终状态为 **PARTIAL**。

## 当前仓库

| 仓库 | 分支 | 当前 HEAD | 工作树 | 判定 |
|---|---|---:|---|---|
| `C:\Users\ylxyl\OneDrive\Desktop\WindyPit` | `windypit-1.8-threaded` | `395fe4765d2757b3b567fa2eeabe358cbf12e17c` | prompt/docs、`AGENTS.md`、`.review` 有未提交内容；服务端提交本身存在 | 可复现但未发布 |
| `C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release` | `thepitultimate-1.8-threaded` | `729e23948cd4238d5f8ce2166236cc10f04fe59d` | `.gitignore`、审查文件、`AGENTS.md`、`.review` 有未提交内容 | 可复现但未发布 |

没有执行 reset、clean、强制 checkout 或覆盖式迁移。所有未跟踪证据必须继续保留。

## 本轮新验证

### WindyPit

命令：

```powershell
cd C:\Users\ylxyl\OneDrive\Desktop\WindyPit
.review\harness\mvn-jdk25.bat -o -B -pl WindSpigot-Server test
.review\harness\mvn-jdk25.bat -o -B clean install
```

两次命令均退出 0；服务端测试为 **180 tests, 0 failures, 0 errors, 0 skipped**。`clean install` 以 Java 25 `--release 25` 编译并生成 `WindSpigot-Server\target\WindSpigot.jar`。本轮 clean install 后 jar SHA-256：

```text
1D53F2BC5ACA4B131E649DA25C9400098868C94975191E4082CC4999A2CF743A
```

`git diff --check` 通过。源码复核确认 `WorldTicker` 仍在主线程执行 `WorldServer.doTick()` / `tickEntities()`；`WorldRegionRuntime` 当前只把不可变 tracker snapshot 交给 owner 做可见性规划，`NmsTrackerPlanApplier` 再在主线程应用 add/remove，不能称为完整 NMS 世界/实体并行。

### ThePitUltimate

命令：

```powershell
cd C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
${env:JAVA_HOME} = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
.\gradlew.bat :base:test --offline --console=plain --no-daemon --rerun-tasks
.\gradlew.bat :base:check --offline --console=plain --no-daemon
.\gradlew.bat :base:build --offline --console=plain --no-daemon --rerun-tasks
```

三条命令均退出 0。当前 `base\build\test-results\test` 的 247 个 XML 合计 **1559 tests, 0 failures, 0 errors, 0 skipped**。构建产物 `base\build\libs\ThePitUltra-build-1.jar` SHA-256：

```text
C74459870C6843CD8FE3670324C82BCE0EF8A23275BDD0DB33FA5CC5E23F241A
```

`git diff --check` 通过。`CombatDamagePlan/Calculator`、`VisualChangeDetector`、`WindyPitBotAdapter` 和 `CommerceTransactionLog` 等类型存在，但验收复核仍发现 combat calculator、bot/NPC owner 路由以及除 market buy 之外的 commerce ledger 没有完整生产消费者，因此不能把它们标为 SUPPORTED。

## 已有运行证据与边界

证据目录：

- 服务端：`C:\Users\ylxyl\OneDrive\Desktop\WindyPit\.review\windypit-1.8-threaded\dsh-run\20261018-0030\`
- 插件：`C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release\.review\thepitultimate-1.8-threaded\dsh-run\20261018-0030\`

已证实：

- 裸 WindSpigot 协议 47 probe 能登录、加入、移动和背包点击；REGION smoke 有两个命名 owner 线程，见 `P10-protocol47-probe.json`、`P10-windypit-tps.txt`。
- ThePitUltra 能启动，首次任务后 adapter 从 Bukkit fallback 升级到 `WINDYPIT_KERNEL`，并能干净 drain；见 `cross-repo-live.md`、`P40-plugin-smoke3-stdout.log`。
- market buy 有 durable ledger 和子进程 `Runtime.halt(42)` 的 RESERVED/COMMITTED 证据；其他交易域尚未覆盖。

不能据此宣称：

- ThePit 真实 1.8.8 客户端的 join/combat/GUI/market/trade/mailbox/NPC parity；同一旧 probe 在 ThePit 启用时于 VarInt 阶段断开。
- 完整世界/实体 tick 并行或玩家战斗异步化。
- 20/50/100 玩家压力、combat JFR、50ms+ 栈分类或完整 parity。

现有 live 日志还记录 `repo.panda-lang.org` Adventure 构件 404、缺少 `com.sk89q.worldedit.world.World`，以及停服时 HologramRunnable 的 `Not scheduled yet` 警告。这些必须在下一轮修复或明确隔离，不能把“插件进程退出”当作无错误发布证据。

## 当前门禁结论

| Gate | 状态 | 原因 |
|---|---|---|
| Java 25、服务端编译/180 单测 | PASS | 本轮命令退出 0 |
| 插件 Java 25、1559 单测/check/build | PASS | 本轮命令退出 0 |
| owner tracker 规划与 shutdown drain | PASS（范围受限） | 仅 tracker 规划，不是完整世界 tick |
| ThePit 生产 combat/bot/commerce 接线 | PARTIAL | 仍有孤立 plan/calculator、bot 路由和 market-only ledger |
| 真实 1.8.8 ThePit gameplay | PARTIAL | enabled-server probe closes during VarInt; no complete real-client gameplay session. Debug the internal protocol/dependency path first; only an unavailable real client after internal repair can make the live-client subgate BLOCKED_EXTERNAL |
| 压测/JFR/release gate | NOT_STARTED | 无目标 PvP 压测与 combat JFR |

下一轮必须从 `45-NEXT-ROUND-MASTER.md` 开始，按编号执行专项 prompt；任何一项必需 gate 未过都不得写 `RELEASE_READY`。

