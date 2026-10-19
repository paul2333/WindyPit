# P4 — 停服 drain 三个缺口收口（W1-SHUTDOWN-DRAIN）

- 仓库：`C:\Users\ylxyl\OneDrive\Desktop\WindyPit`
- 分支：`windypit-1.8-threaded`
- HEAD：`395fe4765d2757b3b567fa2eeabe358cbf12e17c`（未提交；本轮改动保留在工作树）
- 构建：`.\.review\harness\mvn-jdk25.bat -o -B -pl WindSpigot-Server test` / `install`
- 证据目录：`.review\windypit-1.8-threaded\dsh-run\20261018-1538-final\`
- 结论：三个缺口全部收口并接入 `MinecraftServer.stop()`；全量 **219/219** 测试通过，`mvn install` **BUILD SUCCESS**。

---

## 1. 基线（改动前）

| 项目 | 结果 | 证据 |
|---|---|---|
| 全量 `mvn test` | `Tests run: 207, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS | `p4-baseline-mvn-test.log` |

基线说明：工作树中本轮之前的未提交改动全部保留，未执行 `git commit/reset/clean/push`，未触碰 `ThePitUltimate-release`。

---

## 2. 缺口审计（file:line）

| # | 缺口 | 审计时证据（file:line） | 现状 |
|---|---|---|---|
| 1 | CraftScheduler 异步线程池没有 shutdown | `org/bukkit/craftbukkit/scheduler/CraftScheduler.java:91`（`ThreadPoolExecutor executor` 构造，`ArrayBlockingQueue(4096)`，daemon 线程）；全仓库无 `executor.shutdown()` 调用 | 新增 `shutdownAsyncExecutor(timeoutMs)`：`CraftScheduler.java:486` |
| 2 | FastNetworkManager 每连接 tracking 队列残余帧没有停服 flush/drain | `com/falchus/spigot/optimizations/FastNetworkManager.java:19`（`BlockingQueue<Packet<?>>[] queues`）；`:52` `queuePacket`；`:73` `flushQueuedPackets` 只由 `PlayerConnection.java:2682` 每 tick 调用；`:98` `discardQueuedPackets` 只在 `NetworkManager.close()`（`NetworkManager.java:304-306`）调用，无跨连接停服 drain | 新增 `drainAll()` / `drainAll(long)` / 静态聚合 `drainAll(Iterable<NetworkManager>)`：`FastNetworkManager.java:121/125/203/207`；统计类 `DrainStats`：`:259` |
| 3 | ChunkIOExecutor 的 AsynchronousExecutor 没有显式关闭 | `org/bukkit/craftbukkit/chunkio/ChunkIOExecutor.java:16`（静态 `AsynchronousExecutor instance`）；`org/bukkit/craftbukkit/util/AsynchronousExecutor.java:235`（`ThreadPoolExecutor pool`），无 close/shutdown；`ChunkIOExecutor.tick()` 只调用 `finishActive()` | 新增 `ChunkIOExecutor.close(timeoutMs)`：`ChunkIOExecutor.java:50`；底层 `AsynchronousExecutor.close(timeoutMs)`：`AsynchronousExecutor.java:411` |

补充审计：`MinecraftServer.stop()` 原有 `windyTick.shutdownDrain(30000L)`（`MinecraftServer.java:532`）覆盖 pipeline/worldRegions/compute/region/global，但不覆盖上述三个池/队列；`disablePlugins()` 在 `:545`；`ServerConnection.stopServer()` 在 `:558`；`saveChunks(false)` 在 `:573`。

---

## 3. RED（先失败测试）

- 命令：`.\.review\harness\mvn-jdk25.bat -o -B -pl WindSpigot-Server test`
- 日志：`p4-red-mvn-test.log`
- 结果：`EXIT=1`，测试编译失败，未执行任何测试。缺失符号（节选）：
  - `FastNetworkManager.DrainStats`、`drainAll()`、`drainAll(List<NetworkManager>)`、`discardedPacketCount()`、`rejectedPacketCount()`
  - `CraftScheduler.shutdownAsyncExecutor(long)`、`isAsyncExecutorShutdown()`、`asyncExecutorShutdownTimedOut()`、`pendingAsyncTaskCount()`
  - `ChunkIOExecutor.close(long)`、`isClosed()`
  - `AsynchronousExecutor.close(long)`、`isShutdownTimedOut()`、`pendingTaskCount()`
- 说明：这是严格 test-first 的 RED；未删除/跳过任何测试，未放宽断言。

---

## 4. GREEN（实现后）

| 运行 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 定向测试 | `mvn ... test -Dtest=FastNetworkManagerDrainTest,CraftSchedulerAsyncShutdownTest,AsynchronousExecutorCloseTest,ChunkIOExecutorCloseTest,ShutdownDrainWiringSourceContractTest,FastNetworkManagerTest` | `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS | `p4-green-targeted3.log` |
| 全量测试 | `mvn ... -pl WindSpigot-Server test` | `Tests run: 219, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS | `p4-full-mvn-test.log` |
| 安装 | `mvn ... -pl WindSpigot-Server install` | `Tests run: 219, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS | `p4-full-mvn-install.log` |

计数：基线 207 + 新增 12 = **219**（新增测试全部计入，无跳过）。

构建环境说明：首次定向运行（`p4-green-targeted.log`）中 3 个使用 Mockito `mock(Plugin.class)` 的测试因 JDK 25 模块访问限制失败（`InaccessibleObjectException: java.lang`，Mockito 2.x CGLIB 与 JDK 25 不兼容），属于测试脚手架问题而非产品代码失败；已改为 `java.lang.reflect.Proxy` 动态实现 `Plugin`、`ChunkIOExecutor` 测试改用关闭后拒绝路径（不触碰参数），随后 `p4-green-targeted2.log` / `p4-green-targeted3.log` 全绿。未删除测试、未放宽断言。

---

## 4.1 全量日志摘要（原文行）

`p4-full-mvn-test.log`：

```
[INFO] Tests run: 219, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`p4-full-mvn-install.log`：

```
[INFO] Tests run: 219, Failures: 0, Errors: 0, Skipped: 0
[INFO] Installing C:\Users\ylxyl\OneDrive\Desktop\WindyPit\WindSpigot-Server\target\WindSpigot.jar to C:\Users\ylxyl\.m2\repository\ga\windpvp\windspigot\windspigot\1.8.8-R0.1-SNAPSHOT\windspigot-1.8.8-R0.1-SNAPSHOT.jar
[INFO] BUILD SUCCESS
```

`p4-baseline-mvn-test.log`：

```
[INFO] Tests run: 207, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## 5. 新增测试清单（12 个）

| 测试类 | 测试方法 | 验证点 | 结果 |
|---|---|---|---|
| `com.falchus.spigot.optimizations.FastNetworkManagerDrainTest` | `drainAllFlushesEveryAcceptedFrameWithASingleFlush` | 5 帧全部写出、恰好 1 次 flush、统计 `accepted=5/flushed=5`、队列清零 | PASS |
| 同上 | `drainAllCountsFramesOfClosedConnectionAsDiscardedAndKeepsGoing` | 连接已关闭时 2 帧计为 discarded、`closedConnections=1`、不静默吞 | PASS |
| 同上 | `drainAllRejectsFramesArrivingAfterTheDrainAndExposesTheCount` | drain 后新帧被拒绝并计入 `rejectedPacketCount()` / `rejectedAfterDrainPackets` | PASS |
| 同上 | `aggregateDrainVisitsEveryConnectionEvenWhenOneIsClosed` | 静态聚合遍历全部连接，关闭连接计入 closed/discarded 且不影响其他连接 | PASS |
| `org.bukkit.craftbukkit.scheduler.CraftSchedulerAsyncShutdownTest` | `shutdownDrainsAcceptedAsyncTasksBeforeReturning` | 已接受的 2 个异步任务在超时内全部执行完 | PASS |
| 同上 | `shutdownTimesOutAndRecordsExplicitRecoverableState` | 阻塞任务导致 `false`，`asyncExecutorShutdownTimedOut()=true`；释放后二次 await 返回 true | PASS |
| 同上 | `shutdownWithoutTasksSucceedsAndIsIdempotent` | 空池 shutdown 成功且幂等 | PASS |
| 同上 | `asyncTaskScheduledAfterShutdownIsRejectedWithoutRunning` | 关闭后调度的异步任务不执行（Bukkit 语义不被破坏） | PASS |
| `org.bukkit.craftbukkit.util.AsynchronousExecutorCloseTest` | `closeWaitsForAcceptedTasksAndRejectsNewOnes` | close 等待已提交 stage-1；关闭后 `add` 抛 `RejectedExecutionException` | PASS |
| 同上 | `closeTimesOutAndReportsRecoverableState` | 阻塞 stage-1 导致 `false` + `isShutdownTimedOut()=true`；释放后二次 close 成功 | PASS |
| `org.bukkit.craftbukkit.chunkio.ChunkIOExecutorCloseTest` | `closeShutsDownTheStaticExecutorAndRejectsNewChunkLoads` | `ChunkIOExecutor.close()` 成功、`isClosed()`、幂等、关闭后拒绝新 chunk load | PASS |
| `com.windpvp.windspigot.scheduler.ShutdownDrainWiringSourceContractTest` | `stopWiresEveryShutdownDrainStepInOrder` | `MinecraftServer.stop()` 七个调用点各出现一次且顺序正确 | PASS |

---

## 6. 修改清单

### 6.1 生产代码

| 文件 | 修改 |
|---|---|
| `WindSpigot-Server/src/main/java/com/falchus/spigot/optimizations/FastNetworkManager.java` | 新增 `drainAll()`（实例 + 静态聚合）、`DrainStats`（accepted/flushed/discarded/unconfirmed/rejectedAfterDrain/overloadDropped/totalDiscarded/remainingQueued/timedOut/failure）、`discardedPacketCount()`、`rejectedPacketCount()`；`queuePacket`/`writePacketLazily` 在 drain 后拒绝并计数；`discardQueuedPackets` 计入 discarded；关闭连接 drain 时计数并继续 |
| `WindSpigot-Server/src/main/java/org/bukkit/craftbukkit/scheduler/CraftScheduler.java` | 新增 `shutdownAsyncExecutor(long)`（`shutdown()` + `awaitTermination`，超时返回 false 并记录 WARNING）、`isAsyncExecutorShutdown()`、`asyncExecutorShutdownTimedOut()`、`pendingAsyncTaskCount()`；幂等 |
| `WindSpigot-Server/src/main/java/org/bukkit/craftbukkit/util/AsynchronousExecutor.java` | 新增 `close(long)`（closed 标记 + `pool.shutdown()` + `awaitTermination`，超时返回 false 并记录 active/queued/finished）、`isClosed()`、`isShutdownTimedOut()`、`pendingTaskCount()`、`finishedTaskCount()`；`add()` 关闭后抛 `RejectedExecutionException`，并修复 `pool.execute` 被拒时的任务表残留 |
| `WindSpigot-Server/src/main/java/org/bukkit/craftbukkit/chunkio/ChunkIOExecutor.java` | 新增静态 `close(long)`、`isClosed()`、`shutdownTimedOut()`、`pendingTaskCount()`，委托静态 `AsynchronousExecutor` |
| `WindSpigot-Server/src/main/java/net/minecraft/server/ServerConnection.java` | 新增 `drainFastNetworkQueues()`：`addPending()` 后在连接列表锁内快照，调用 `FastNetworkManager.drainAll(...)` |
| `WindSpigot-Server/src/main/java/net/minecraft/server/MinecraftServer.java` | 在 `stop()` 接入三个调用点（见 §7）；网络 drain 统计失败时 `LOGGER.error`，否则 info；chunkIO/scheduler 超时记录 error |

### 6.2 新增测试（5 个文件，12 个测试）

- `WindSpigot-Server/src/test/java/com/falchus/spigot/optimizations/FastNetworkManagerDrainTest.java`
- `WindSpigot-Server/src/test/java/org/bukkit/craftbukkit/scheduler/CraftSchedulerAsyncShutdownTest.java`
- `WindSpigot-Server/src/test/java/org/bukkit/craftbukkit/util/AsynchronousExecutorCloseTest.java`
- `WindSpigot-Server/src/test/java/org/bukkit/craftbukkit/chunkio/ChunkIOExecutorCloseTest.java`
- `WindSpigot-Server/src/test/java/com/windpvp/windspigot/scheduler/ShutdownDrainWiringSourceContractTest.java`

测试全部为轻量对象/真实调度器实例（动态 Proxy 插件、EmbeddedChannel、测试 Provider），不依赖真实服务器。

---

## 7. `MinecraftServer.stop()` 实际调用顺序

实际代码顺序（行号为当前工作树）：

1. `SpigotTimings.stopServer()` — `MinecraftServer.java:526`
2. `windyTick.shutdownDrain(30000L)` — `:532`
3. `this.server.disablePlugins()` — `:545`
4. `this.aq().drainFastNetworkQueues()`（FastNetworkManager.drainAll 聚合） — `:551`
5. `this.aq().stopServer()` — `:558`
6. `this.v.savePlayers()` / `this.v.u()` — `:562-564`
7. `this.saveChunks(false)` — `:573`
8. `ChunkIOExecutor.close(10000L)` — `:587`
9. `CraftScheduler.shutdownAsyncExecutor(10000L)` — `:615`（位于 `WindSpigot.getInstance().shutdown()` 之后、`DedicatedServer.z()` 的 `System.exit(0)` 之前）

与 Prompt 建议顺序一致；唯一差异是“保存玩家”保留在 `stopServer` 之后、保存世界之前（既有 CraftBukkit 顺序，未改动）。`ShutdownDrainWiringSourceContractTest` 对 1→8→9 的关键顺序做了源码级锁定。

---

## 8. 构建产物

- 路径：`C:\Users\ylxyl\OneDrive\Desktop\WindyPit\WindSpigot-Server\target\WindSpigot.jar`
- 大小：`61,294,894` bytes
- SHA-256：`D953CADC1F96412EDEC6DD6B1DA27E404951520D81C78DDCC7A44E1F36273133`
- 安装位置：`C:\Users\ylxyl\.m2\repository\ga\windpvp\windspigot\windspigot\1.8.8-R0.1-SNAPSHOT\windspigot-1.8.8-R0.1-SNAPSHOT.jar`
- 对比：上一轮 207 测试 jar `BE593B0F6655399513D50F8813995E260F99FF52A11C10A30785D33E52062EE9` → 本轮 `D953CADC...`（代码变化导致，符合预期）

---

## 9. 剩余风险（明确、可恢复）

1. **异步任务阻塞主线程回调**：`CraftScheduler` 的插件异步任务若在任务内等待 `callSyncMethod`（需要主线程 heartbeat），停服时主线程已停止 heartbeat，`shutdownAsyncExecutor(10000)` 会超时返回 false 并记录 `pendingAsyncTaskCount()`；daemon 线程不会阻止 JVM 退出，该任务会被放弃。状态：`asyncExecutorShutdownTimedOut()=true` + WARNING 日志，可恢复（下一轮可加“先 cancel 所有插件任务再 await”策略）。
2. **Spigot404Write 静态队列**：`Spigot404Write.packetsQueue` 是跨连接的静态队列，本轮按 Prompt 选择在 `MinecraftServer.stop()` 调 `FastNetworkManager.drainAll`，未单独 drain 该静态队列；若停服瞬间仍有 `writeThenFlush` 残余，仍可能丢失（该队列不在三个指定缺口内，建议后续单列）。
3. **AsynchronousExecutor 的 stage2/stage3 回调**：`close()` 只等待 stage-1 完成并终止池；已完成但仍在 `finished` 队列中的回调不会在停服时执行（`finishedTaskCount()` 可见）。因世界已保存，不会造成“保存丢失”，但会留下未应用的 chunk 回调。
4. **网络 flush 的确认边界**：`drainAll` 等待最后一个 write future；若 event loop 卡住导致超时，帧可能已写出但未确认，统计记 `unconfirmedPackets` + `failedConnections` + `timedOut=true` 并 `LOGGER.error`，不会静默。
5. **`ServerConnection.stopServer()` 既有行为**：仅当存在监听 channel 时才 shutdownGracefully；无监听 channel 时 event loop 不会关闭（既有问题，本轮未改）。
6. **本轮未做真实服务器停服 smoke**：验证范围为单元测试 + 源码顺序契约 + 全量构建；真实停服 smoke 建议在下一轮用既有 `P65b-run-smoke.ps1` 复跑（本轮未改动该脚本）。

---

## 10. 约束遵守

- 未调用 Claude Code / Claude CLI（全部通过 pwsh + 文件工具完成）。
- 未执行 `git commit` / `git reset` / `git clean` / `git push`。
- 未改动 `ThePitUltimate-release` 仓库。
- 工作树既有未提交改动全部保留，仅新增/修改上述文件。
- 未删除、未跳过、未放宽任何测试；`Skipped: 0`。

