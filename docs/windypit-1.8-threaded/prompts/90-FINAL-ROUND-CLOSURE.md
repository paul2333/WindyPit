
```powershell
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.net=ALL-UNNAMED
--add-opens java.base/java.nio=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

先等待服务器 stdout 明确出现 `Done (`，然后额外等待约 1 秒，再启动客户端/发协议探针/执行第一条游戏交互，避免客户端连接抢在监听器和插件初始化完成前。保存带时间戳的启动日志，并检查 ThePit 实际 enable 与 adapter 模式。

使用本机已安装的真实 Minecraft 1.8.9 客户端（历史路径：`C:\Users\ylxyl\OneDrive\Desktop\PCL\LatestLaunch.bat`，用户 `PitTester`）连接本轮新构建的 WindSpigot + ThePit。目标协议仍为 47。至少完成并记录：登录/加入竞技场、实际移动、真实近战伤害与击退、弓箭或投射物命中、死亡/重生与奖励、一个 ThePit GUI 事务（市场/邮箱/交易选其一并完成真实点击与服务端状态确认）。另测一次 disable/stop 后干净关闭。每项记录客户端操作、对应服务端事件/日志、profile/ledger 前后值和截图或等价可复核日志；只看到登录或 `Taking Inventory` 不得标记完整 gameplay pass。

上一轮的 OpenGL 客户端窗口输入偶发无法聚焦。先等 `Done (` + 1 秒，然后验证客户端窗口前台焦点、按键/鼠标实际送达、服务器确有游戏事件；若工具输入不可靠，换已安装的可重复客户端控制方案或实际人工会话，不得继续把失败动作算作已完成。只有客户端/真实操作人员确实不可用且内部握手/插件已修好时，才将未做的特定 live subgate 标成 `BLOCKED_EXTERNAL`，并列出已尝试动作、日志和重测步骤。

## 阶段 5：时长符合门槛的压力测试和 JFR

当前 0945 的 0/20/50/100 数据是约 60 秒移动/网络负载，已有 TPS `20.01–20.02`、world tick p99 最大 `18.63 ms`、JFR 对该批短录制未发现未解释 server-thread 50ms+ duration event。它是有用的短时结果，但不是 release gate。

1. 使用 Java 25、固定硬件/配置/world/jar/GC 参数，记录每轮完整 manifest。相同条件记录 baseline 和 optimized；若没有能校验的 baseline artifact，写清楚，不得编造百分比收益。
2. 跑 0 玩家、20、50、100 协议负载、混合插件负载，以及两个非竞技 owner 对照。正式有负载场景 warm-up 至少 5 分钟、测量至少 10 分钟。保留现有 `runtime/tools/load_clients.py` 的移动/网络场景，但标签必须说明它不做 PvP；增加/使用真实战斗客户端或明确可验证的 PvP scenario，覆盖伤害、投射物、死亡奖励和 GUI/commerce。
3. 每个有负载场景至少抓 5 分钟 JFR。优先用 repo 的 `runtime/tools/jfr_profile.py`；运行外部 `jcmd` 时使用当前 Java 25 同版本。导出真实 duration events >=50ms 的完整栈，分成 owner/core blocking、DB/file I/O、GC、safepoint、network、intentional tick pacing/park、unexplained。ExecutionSample 只能报告为采样，不当成调用耗时。
4. 固定数值门槛：TPS 平均 >=19.5，tick duration p99 <=50ms，所有可重复核心 owner >=50ms duration stack 有原因分类且无未解释阻塞，accepted work 无静默丢失，combat/gameplay 对照无行为差异。单独报告每轮 MSPT/tick p50/p95/p99、combat/movement latency、tracker/owner/SQLite queue、rejection/stale/drop、GC pause、packet count、bot 数、feature flags 和在线数。
5. 有任一指标不达标时，先定位真实热点、用 JFR/IDEA profiler 证明改动关联，再修并重跑受影响场景。不要根据观察到的 sampled stack 做无证据“微优化”。

## 阶段 6：最终新鲜验收命令与 artifact gate

使用 Microsoft OpenJDK 25.0.3.9；记录 `java -version`。如 wrapper 路径和仓库脚本有变化，先确认等价命令和 JDK 版本并保存原命令。

```powershell
# WindyPit
cd C:\Users\ylxyl\OneDrive\Desktop\WindyPit
.review\harness\mvn-jdk25.bat -o -B -pl WindSpigot-Server test
.review\harness\mvn-jdk25.bat -o -B install

# ThePitUltimate
cd C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
.\gradlew.bat :base:test --offline --console=plain --no-daemon --rerun-tasks
.\gradlew.bat :base:check --offline --console=plain --no-daemon
.\gradlew.bat :base:build --offline --console=plain --no-daemon
```

再执行 `git diff --check`、当前 jar hash、JUnit XML 测试计数、artifact boundary scan、强依赖/旧包/NMS/ProtocolLib 引用扫描、同步 DB/文件 I/O 热路径扫描、启动/停服和每个 feature flag 的 rollback 检查。对允许的旧 bridge、服务端内部 NMS 和可选 ProtocolLib capability 做 allowlist 并解释，不能把所有命中都忽略。

## 对抗式复核清单

由未实现对应改动的只读审查者或新上下文逐条找反例：

1. 有接口没有生产 caller；caller 在未注册/错误 owner 仍访问 Bukkit/NMS。
2. async 结果在 generation 变更、quit、death、unload、transfer 后覆盖了新状态。
3. queue saturation 触发调用线程执行、漏计、丢事务或回滚没恢复旧路径。
4. durable commit 和物品/点数/权限/杀戮奖励的实际可见副作用不一致或重启重复。
5. plugin disable 次序先关数据库/owner queue，后面还有 timer/packet/callback 写数据。
6. 可选依赖不存在时 class initialization/静态引用仍崩溃；卸载时 listener 未清理。
7. 只测 mock/harness，没有证明生产代码路径；或压力输入没有实际触发声称的游戏行为。
8. TPS/p99 报告来自 idle、短样本、单tick，或者 JFR 栈把采样估算误标成 duration。
9. 配置被两个系统各自默认化并写回，未知键/旧键丢失。
10. 测试被删减、跳过、并行资源争用导致 flake 后未串行复跑。

只读审查发现问题时，先由总控复现并修复，再重新跑受影响测试与最终完整 suite。不要把审查代理的结论直接贴为事实。

## 本轮报告格式与退出谓词

更新两仓库本轮 `.review/.../<round-id>/FINAL-REPORT.md`、`ACCEPTANCE-VERIFICATION.md`、`run-index.md`。结论表至少列：完整玩法/战斗 parity、durable 每域崩溃恢复、owner 线程生产消费、配置 schema 单一所有者、依赖 closure、协议 47 真实客户端 gameplay、0/20/50/100 与混合长时压力、JFR >=50ms 栈、rollback、clean startup/shutdown；每行只能为 `PASS` / `PARTIAL` / `FAIL` / `BLOCKED_EXTERNAL`，并引用本轮文件名、原始命令和退出码。

工作完成的定义是：所有本轮内部可解决的问题已实际修复并测试；所有生产域有证据；真实 gameplay 和规定时长的负载/JFR 已运行；双仓库 artifact 及关闭验证通过；独立审查完成；最终报告准确反映证据。不能以写报告、列出剩余 todo 或“实现理论上安全”作为停止条件。

如果真实客户端人工动作或机器资源仍无法提供，先完成全部代码、crash matrix、构建、启动、非玩家压力、owner 安全和内部修复；最后只将确实缺人的实测门槛标记 `BLOCKED_EXTERNAL` 并给出复测操作。若完整 `WorldServer.doTick()`/`tickEntities()` 无法安全移到多个 owner，则保持安全同步实现并报告 `PARTIAL`；不可伪称已经实现全世界并行或发布就绪。

最终只在所有成功条件均有本轮证据时输出 `RELEASE_READY`。否则输出 `PARTIAL` 或具体 gate 的 `BLOCKED_EXTERNAL`，列出已验证产物/测试、唯一剩余阻塞及可直接复测命令；不删改旧证据，不提交、不推送。

