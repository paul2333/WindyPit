# Prompt 80 — 双仓库压力、JFR 与最终发布门禁

仓库：两仓库；实现已完成后由 DSH 作为只读 verifier 执行。不得调用 Claude Code。没有真实客户端时仍须完成内部 harness 和 JFR，最后只把缺失 live 环节标 `BLOCKED_EXTERNAL`。

## 固定环境

- Microsoft OpenJDK 25.0.3；同一机器、同一 WindSpigot jar、ThePit jar、world、内存和配置做 baseline/optimized 对照。
- 目标协议仍 1.8.8/47。不要以 Folia 26.x 或空闲服务端数据代替。
- 每场景 warmup 5 分钟，测量 10 分钟；无法达到时写明实际时长，不得改门槛。

## 场景

0 玩家、20/50/100 玩家共享竞技场、bot/NPC、五类视觉、market/trade/mailbox、audit/backup/replay、混合压力，以及多个独立 world owner 对比。保存玩家数、bot 数、feature flags、Java/GC 参数、WindSpigot/ThePit revision。

## 指标和固定门槛

记录 TPS、MSPT/tick p50/p95/p99、combat/movement latency、packet count、tracker/owner/SQLite/remote queue depth、rejection/stale/drop、GC pause、SQLite latency、每玩家 tick task 数。门槛固定为平均 TPS >= 19.5、tick p99 <= 50ms、无重复未解释核心 owner 50ms+ 栈、无已接受 commerce/storage work 丢失、无行为差异；20.00 TPS 另行报告。

## JFR

每个有效压力场景启动至少 5 分钟 JFR，使用 Java 25 `jcmd <pid> JFR.start name=windypit settings=profile duration=5m filename=<absolute>.jfr` 或仓库 `runtime/tools/jfr_profile.py`。用 `jfr print`/脚本导出全部超过 50ms 的事件和调用栈，按 core-owner block、DB/file IO、GC、safepoint、network、intentional pacing、unexplained 分类。空闲 JFR 不得成为 PvP 结论；未解释的 repeated core-owner stall 阻止发布。

## 验收命令与产物

```powershell
cd C:\Users\ylxyl\OneDrive\Desktop\WindyPit
.review\harness\mvn-jdk25.bat -o -B clean install
cd C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
.\gradlew.bat :base:test --offline --console=plain --no-daemon --rerun-tasks
.\gradlew.bat :base:check --offline --console=plain --no-daemon
.\gradlew.bat :base:build --offline --console=plain --no-daemon
```

在新的双方 `.review\<round-id>` 保存 raw load output、JFR、解析表、baseline 对照、jar hashes、协议/关闭/回滚日志和 `ACCEPTANCE-VERIFICATION.md`。重新运行 artifact boundary scan、静态 owner/race/IO 搜索、hard-halt recovery 和 plugin disable/server stop。只要 parity、真实客户端、恢复、压力或 JFR 任一必需 gate 未过，最终状态就是 `PARTIAL` 或 `BLOCKED_EXTERNAL`，不得写 `RELEASE_READY`。

