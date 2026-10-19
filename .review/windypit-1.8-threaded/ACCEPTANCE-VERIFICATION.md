# WindyPit 1.8.8 多线程改造 — 双仓库验收报告

- 验收日期：2026-10-17
- 验收方式：只读审计 + 构建实测（未修改任何源码/测试/配置；构建产物 target/build 不计改动）
- 验收执行：主控 + 5 个并行子代理（SA1 服务端架构 / SA2 插件架构 / SA3 服务端构建实测 / SA4 插件构建实测 / SA5 交付物对账）
- 服务端仓库：`C:\Users\ylxyl\OneDrive\Desktop\WindyPit` @ `windypit-1.8-threaded`（HEAD 6b64ceb）
- 插件仓库：`C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release` @ `thepitultimate-1.8-threaded`（HEAD 8240fb3）

---

## 1. 构建门禁实测（一手证据）

### 服务端（SA3 实测）
| 项目 | 结果 |
|---|---|
| JDK | openjdk 25.0.3 LTS (Microsoft)，Maven 3.9.16，harness 脚本在位 |
| `mvn-jdk25.bat -o -B clean install` | **EXIT 0**，3/3 模块 SUCCESS，47.5s，ERROR=0 / WARNING=235（deprecation+shade，无阻断）|
| 在线重跑 | EXIT 0，与离线一致（50.4s）|
| `-pl WindSpigot-Server test` | **Tests run: 138, Failures: 0, Errors: 0, Skipped: 0**，33 测试类全绿 |
| 产物 | WindSpigot.jar 58.4MB + original 61.4MB + remapped + API jar（12:43-44 生成）|

测试覆盖实测：scheduler 20 类（GlobalRegion/Region FIFO/OwnerThread/BoundedQueue/ShutdownDrain/EntityScheduler/EntityOwnership/ComputePool/BotBudget/DurableOperationLog/NetworkSendQueue/TaskRetirementToken/ThreadViolationReporter/TickPipeline/TrackerAsyncEngine/TrackerVisibilityPlanner/Diagnostics 等）、world 6 类、combat 2 类、config 2 类、async 2 类、网络/数据库/兼容各 1 类。

### 插件（SA4 实测）
| 项目 | 结果 |
|---|---|
| JDK / Gradle | 25.0.3+9-LTS / wrapper 9.8.0 |
| 模块结构 | settings 仅 `:base`；folia/、pit-core/ 目录不在构建内；`:folia:test` 不存在 → **替代命令 `:base:test :base:jar` 已按 prompt 规则记录于 ledger**（合规）|
| `:base:test --offline --rerun-tasks` | **EXIT 0，Tests run: 1473, Failures: 0, Errors: 0, Skipped: 0**（234 个 JUnit XML 汇总）|
| flaky 测试 | PlayerActivityReadinessTest 本轮**通过**（已由 mockito answer 修复，CCE 根因消除）|
| `:base:build` | EXIT 0；base-build-1.jar 4.6MB（2276 entries）+ ThePitUltra-build-1.jar 26.2MB fat jar（7028 entries）|
| plugin.yml | name=ThePitUltra, main=cn.charlotte.pit.ThePit, **api-version=1.8**, depend=[LuckPerms, ProtocolLib, PlaceholderAPI] — 1.8 语义正确 |
| 编译警告 | 22 个 unchecked/deprecation，非错误 |

## 2. Artifact 边界扫描实测（主控一手，非转述）

- 服务端 `WindSpigot.jar`（29368 entries）+ `WindSpigot-API.jar`（939 entries）：**CLEAN，EXIT=0**
  - EXEMPT 34 类上游 1.8.8 基线 adventure-key/examination 子集（有记录豁免）
  - WARN 13 类 legacy io/papermc/paper 1.8.8 条目（上游遗留，非本轮引入）
- 插件 fat jar 实测：`io/papermc/paper` / `net/kyori/adventure` / `DataComponent` / `org/bukkit/persistence` 条目 **全部 0 命中**
- **结论：现代 Paper/Folia/Adventure/DataComponent API 未进入 1.8 产物，边界门禁 PASS**

## 3. 交付物对账结论（SA5，行号级实证）

### 总判定
**无恶意虚报**。所有 DONE_VERIFIED 核心声称（文件、@Test 计数、audit log、接线、恢复类）全部实锤；测试演进链 96→70→87→107→115→121→131→137→138 在 11 个 audit log 中逐一吻合可追溯。账实偏差集中于"多做少记"（账面滞后于实际工作），仅 1 处硬虚报。

### 虚报/账实偏差清单
| # | 位置 | 问题 | 严重度 |
|---|---|---|---|
| 1 | 插件 parity-matrix.csv:15 | "diagnostics → plugin reads via adapter, DONE_VERIFIED"：WindyPitSchedulerAdapter.java 中 grep diagnostic/snapshot/report 零命中，**读取面不存在** | **硬虚报** |
| 2 | 服务端 final-gate.md:37 | 声称服务端有 WindyPitBotAdapter——全仓库无此类（在插件仓库），跨仓库引用未标注 | 误导 |
| 3 | 服务端 final-gate.md:12 | Tests 137，实际 138（FINAL-VERIFY log 实测），未回填 | 陈旧 |
| 4 | 服务端 ledger 行13 W2-TRACKER | "NMS feed pending" 账面落后：TrackerShadowFeed.java 已被 MinecraftServer.java:1072 调用、138 全绿 log 在 | 做了未入账（好方向）|
| 5 | 服务端 ledger 行18 W4-STORAGE | DONE_VERIFIED 占位行：files/tests 与 W4-CRASH 完全相同，零独立交付 | 占位 |
| 6 | 两仓库 ledger 行2 | dirty-manifest/baseline 停在 08:33 W0 快照，W1-W6 产出未入清单 | 未兑现 |
| 7 | 服务端 final-gate.md:16-18 | jar 尺寸/sha 过期（12:45-46 重建无 audit log）| 陈旧 |
| 8 | 插件 final-gate.md:12 | 1470/0/0，实际 1473/1（flaky 已双披露，数字未回填）| 陈旧 |
| 9 | 插件 ledger 行16 | files 漏列 WindyPitBootstrap(.Test).java（实际交付多于声明）| 好方向偏差 |
| 10 | 插件 parity-matrix.csv:18 | crash_recovery DONE_VERIFIED 过乐观：仅单元级，hard-halt 真实演练缺 | 过乐观 |

### 空壳交付物
- 服务端 `.review/windypit-1.8-threaded/`：`performance/`、`jfr/`、`crash-recovery/` 三个**空目录**（无 TPS 数据、无 .jfr、无演练证据）
- `runtime/windypit.yml` 是 Folia launcher 配置（java.home/server.jar/heap），与 prompt 要求的 threading/features/rollback schema **同名异物**，schema 样例缺失

## 4. 集成缺口（主控一手 grep 验证 + SA5 交叉确认）

1. **combat region 绑定：两仓库零任务行** —— W3-COMBAT 交付的是 CombatDamagePlan/Calculator 纯计算管线（零 Bukkit/NMS import），**未绑定 ArenaRegion owner thread**，NMS feed 未做。这是 prompt Wave 3 的核心要求，为最大功能缺口。
2. `MovementAuthFilter` 仅被 WindyPitBootstrap 构造暴露（ThePit.java 行41/103/216/231 接线实锤），生产移动路径（PlayerMoveHandler）无调用——认证过滤未进真实移动路径。
3. `TrackerAsyncEngine` / `NetworkSendQueue` 在 net/minecraft/server 零引用（TrackerShadowFeed 已接线，但异步引擎主体未接）。
4. commerce 幂等（CommerceTransactionLog）未 wire 进 PointLedger/SqliteStore/market/trade/mailbox。
5. NPC：仅 BotBudget 预算（CAS），region-owner 绑定语义无任务行。
6. diagnostics：/windypit-tps 命令未实现（next_action 自认）；插件读取面不存在（虚报项 #1）。
7. pruning：MechanismPruningPlan 计划类完成，NMS WorldServer flag 接线未做。

## 5. 架构深度审计（SA1 服务端 / SA2 插件，行号级证据）

### 5.1 服务端内核（SA1）

**总评：组件库+并发契约质量 ~90%，生产集成 ~25%，内核整体完成度 ~60%。生产实例实际仍是单主线程 + shadow 采样——"多线程只在纸面运行"，threading.mode=region 无实际开关可拨。**

已实锤的优秀实现：
- 调度内核 27 类：RegionThread（ArrayBlockingQueue+拒绝异常+计数）、RegionScheduler（assertRegionThread owner 校验→违规记录+RegionThreadViolationException）、严格 FIFO（测试验证 100/50 任务有序）、shutdownDrain 幂等、ComputePool（generation/regionId/entityId/sequence 四元组 staleCheck）
- 禁止项核查通过：全仓无 cached thread pool、无无界工作队列（唯一静态无界队列在死代码 Spigot404Write.java:16）
- tick pipeline 五阶段存在，TickPipeline.runPhase finally 关 timing 并重抛；WindyPitTickSourceContractTest 锁定 MinecraftServer.java:996/1070/1072 接线"恰好一次"
- 真实网络链路合格：FastNetworkManager 每玩家有界队列（ArrayBlockingQueue×trackingThreads，容量≥64 默认 4096）、溢出丢弃+显式断线"Connection overloaded"、flush 每 tick 一次在 eventLoop 执行、写路径无实体修改无 Bukkit 事件

P1 级问题：
- **P1-1 关闭 drain 死路径**：windyTick.shutdownDrain 从未被 MinecraftServer.stopServer 调用——关服时 region/queue 任务静默丢失，与 RegionScheduler.java:13 "overflow is a visible rejection, never silent loss" 契约冲突
- **P1-2 零生产调用**：ComputePool/TrackerAsyncEngine/TrackerVisibilityPlanner/EntityOwnershipService/EntityScheduler/NetworkSendQueue/WindyPitDiagnostics/BotDecisionDispatcher/BotBudget/DurableOperationLog/DurableStateRecovery 全部零生产构造点——内核以"库+138 测试"形态存在，主线程每 tick 只是泵一个空 arena 队列；WindyPitTickIntegration 的 REGION_SNAPSHOT/ASYNC_COMPUTE 阶段空壳（L31-39 返回 null/空）
- **P1-3 新配置不可达**：WindyPitConfig 的 threading.mode/region-threads/rollback/features 键无部署文件、无启动装载点，运行时不可达

P2/P3 级：RegionThread.generation 恒 1L（重绑定不 bump，陈旧检测全靠 token retirement）；EntityScheduler.pendingTokens 无上限仅 unregister 清理（长寿命实体缓慢泄漏）；GlobalRegionScheduler 固定频率重入队绕过容量检查（L132-134）；TrackerShadowFeed 每 tick 主线程遍历全部世界全部实体构建快照后丢弃（TrackerShadowFeed.java:69-79，零收益纯开销，"later revision" TODO 残留）；TrackerVisibilityPlanner.findCurrent O(E) 线性扫描；Spigot404Write+Tasks 死代码（静态无界队列）；cache/Constants.java 空壳；MinecraftServer.java:1069-1074 注释嵌套重复。

测试盲区：无 NMS 级集成冒烟测试、TrackerShadowFeed 零测试、FastNetworkManager 仅 1 测试。

### 5.2 插件适配（SA2）

**总评：多线程适配 = "未接线组件骨架 + 高质量单测"。组件本体 ~75% 质量分，业务接线 0%、生命周期接线 0%、遗留调度迁移 0%，加权完成度 ~30%。**

已实锤的合格实现：
- WindyPitSchedulerAdapter 428 行：反射门面完整（Class.forName 探测 com.windpvp.windspigot.scheduler.WindyPitTickIntegration + 遍历 MinecraftServer 双映射继承链找 windyTick 字段 + KernelHandles 方法句柄枚举，零编译期依赖），五类任务齐全（arena/global/entity/delayed/timer/cancel/owner 断言），kernel 缺失→Bukkit fallback，RejectedTaskException→主线程回退；fake kernel 桩 8 类钉住反射契约
- CombatDamagePlan 不可变快照（final+builder 零 Bukkit import）+ Calculator 纯管线数值正确（暴击×1.5、护甲 4%/点 80% 帽、附魔减伤 60% 帽、远程 8~24 格衰减）
- 现代 API 泄漏干净：28 处 adventure 全部为 tracked 既有文件经 adventure-platform-bukkit 4.3.2 反射加载（1.8.8 可用），新增包零泄漏；io.papermc/persistence/DataComponent 均 0
- 既有体系真实有效：SqliteDatabase 单线程 writer executor、PlayerActivityService main-thread owner + teleport ignoreMovementUntil 限速、BotCullingPolicy 观察者空间桶裁剪、移动路径无 DB 读取无 getOnlinePlayers 全服扫描（禁止项核查通过）

P0 级问题：
- **P0-1 全部新子系统零消费**：grep "runArenaTask|assertArenaOwner|getWindyPitBootstrap|tryBegin|MovementAuthFilter|VisualChangeDetector|CombatDamagePlan" 在 main 源码仅命中自身+Bootstrap+ThePit 构造点。游戏逻辑仍在原 Bukkit 调度路径运行（ThePit.onEnable 仍直接 runTaskTimer L330/334/335/599）。真实 WindSpigot region 线程下 CombatListener.onCombat（MONITOR）无 owner 断言直接读写双方 profile/inventory、PitBotManager 每 tick 全 viewer 遍历（L1393-1419）、NametagThread 每 20tick 全服遍历（L45）都是未防护的跨线程实体访问——且此类 bug 不可复现于 Bukkit 单线程
- **P0-2 HEAD 检查点编译不自洽**：8240fb3 提交删除 data/temp/TradeRequest.java 但 PitCommands.kt:12、trade/TradeMonitorRunnable:3 仍 import 它——纯 git checkout 后构建直接失败；工作区靠 untracked 副本恢复编译

P1/P2/P3 级：CommerceTransactionLog javadoc L13-14 声称"PENDING entries survive crash"为虚假声明（纯内存无落盘无恢复扫描）且 transactions map 无淘汰→无界增长内存泄漏；MovementAuthFilter SKIP_NOOP 不可达（lastBlock* 只写不读 L52-57）、isYawPitchOnly 快路径未接入 classify、isGateOpen 命名语义反转（返回 true 实为"应拦截"）；adapter trackEntity/untrackEntity 静默吞 Throwable（L277/286）；invokeGlobalFixedRate 失败抛 ISE 不回退（L404-410，与其它路径不一致）；拒绝识别靠异常简单名 equals("RejectedTaskException")（L375）——内核改名即失效；GUI 未改造为 owner command 模式（MarketHolder 仍可变 page/sort）；adapter 把全部任务绑到 integration.arena() 单一共享 region，kernel 桩只钉方法形状无法发现真实 API 语义漂移；NPC/Bot AI 同步主线程 tick 无实体 owner 绑定，"无观察者暂停"仅覆盖可见性/攻击，AI tick 照跑；.bak 备份文件留在 src/main 树内；plugin.yml 中文 GBK 乱码。

测试统计：新增 62 @Test（scheduler 23 含 Bootstrap 3 / combat 10 / visual 11 / movement 8 / commerce 10）+ 8 个 fake kernel 桩类 0 @Test。MovementAuthFilter 的 SKIP_NOOP 无测试——因为该分支不可达。旁证（tracked 既有）：commerce 套件 93、LaunchTrajectory 41。缺测：无任何集成测试证明 PlayerMoveHandler/CombatListener/market 走新路径（因根本未接线）。

### 5.3 两仓库共性模式

**组件层（库、快照、管线、测试）质量普遍良好且测试真实；集成层（生产调用点、生命周期、配置装载、关服路径）大面积缺失。** "多线程改造"目前形态：高质量的引擎零件已造好并测试通过，但尚未装进车里，车仍由原单线程引擎驱动。若误以为"adapter 已就位即线程安全"，在真实 region 线程上跑旧代码会产生不可复现于单线程的跨线程实体访问 bug。

## 6. 验收矩阵（按 prompt §6 门禁）

| 门禁项 | 状态 | 依据 |
|---|---|---|
| 纯规则和 snapshot 单元测试 | PASS | 插件 59 新测试逐一吻合（SA5 对账）|
| WindyPit region/entity owner 测试 | PASS | 15 类内核 + 8 测试类（RegionSchedulerFifo/OwnerThread/BoundedQueue/ShutdownDrain 等）|
| tracker exactly-once 测试 | PASS | TrackerAsyncEngine/VisibilityPlanner 测试 + 基线 WorldTickerTrackerStepExactlyOnceTest 15 |
| 线程违规和死锁 watchdog 测试 | PASS | ThreadViolationReporterTest 等 |
| 队列容量、拒绝和 shutdown drain 测试 | PASS | RegionSchedulerBoundedQueueTest/ShutdownDrainTest |
| ThePit 1.8.8 行为 fixture | PASS | fake contract 8 类 + 59 测试 |
| market/trade/mailbox/奖励守恒测试 | PARTIAL | CommerceTransactionLog 幂等 10 测试存在；**未 wire 进真实存储/市场/交易/邮箱路径** |
| hard-halt 恢复测试 | PARTIAL | DurableOperationLog/DurableStateRecovery 单元级 6 测试；**真实 hard-halt 子进程演练缺**（crash-recovery/ 空目录）|
| artifact boundary 扫描 | PASS | 主控实测双 CLEAN（本报告 §2）|
| WindyPit Java 25 clean build | PASS | SA3 实测（本报告 §1）|
| ThePit Java 25 build | PASS | SA4 实测（本报告 §1）|
| 0/20/50/100 玩家压力报告 | **BLOCKED_EXTERNAL** | performance/ 空目录，无任何实数；需真实 1.8.8 客户端+测试服 |
| JFR 50ms 栈分类报告 | **BLOCKED_EXTERNAL** | jfr/ 空目录；插件声称的输出目录不存在 |

## 7. 最终结论

**最终状态：`BLOCKED_RELEASE`**（final-gate 自报的 RELEASE_READY 措辞偏强，予以降格——其自身脚注已承认 live acceptance pending）。

准确定性（修正 final-gate 的笼统表述）：

- **服务端 = 内核库级完成（~60%）**：组件库+并发契约+测试质量高（138/138 绿、边界 CLEAN、FIFO/owner/drain/exactly-once 语义有专测），但生产集成 ~25%——多线程目前只在测试里运行，threading.mode/rollback 配置键运行时不可达，关服 drain 死路径
- **插件 = 组件骨架完成（~30%）**：adapter/快照/过滤器/幂等日志组件本体+62 单测质量良好，但业务接线 0%——五条业务线（combat/movement/visual/commerce/bot）一条都没接进生产路径，适配层形同孤岛
- 交付物账面无恶意虚报（1 处硬虚报 + 若干账面滞后，见 §3）

**RELEASE 前置条件（必须补齐，按优先级）**：

P0（正确性/可复现性）：
  1. 插件五条业务线真实接线：任一 gameplay 调用点真实经过 adapter 且断言 owner（combat region 绑定、MovementAuthFilter→PlayerMoveHandler、commerce→market/trade/mailbox、visual→scoreboard/nametag、bot→owner）
  2. 服务端生产集成：ComputePool/EntityOwnershipService 等接 NMS 调用点，REGION_SNAPSHOT/ASYNC_COMPUTE 阶段实装
  3. shutdownDrain 接入 MinecraftServer.stopServer（消除关服静默丢任务）
  4. HEAD 检查点一致性：TradeRequest.java 删除/import 残留二选一修复，保证纯 git checkout 可编译
  5. windypit.yml schema 样例（真 threading/features/rollback 键 + 启动装载点）
  6. CommerceTransactionLog：虚假 javadoc 修正 + 淘汰机制（无界增长）+ 落盘或改声明

P1（账面与机制）：
  7. tracker 异步引擎/NMS feed 完整入账 + NetworkSendQueue 接线或移除
  8. NPC region-owner 绑定 + AI tick 暂停
  9. pruning NMS WorldServer flag 接线
  10. diagnostics /windypit-tps 命令 + 插件 adapter 读取面（或改 parity-matrix.csv:15 虚报行）
  11. adapter 健壮性：吞异常改记录、异常名识别改类型识别、fixed-rate 回退一致化

P2（外部验收边界）：
  12. hard-halt 子进程崩溃演练（crash-recovery/ 落真实证据）
  13. 0/20/50/100 玩家压测 + JFR 50ms 栈分类（外部条件：真实 1.8.8 客户端+测试服；当前如实 BLOCKED_EXTERNAL）

P3（账面卫生）：
  14. dirty-manifest/baseline 重刷（W1-W6 产出入清单）、测试数回填（138 / 1473）、W4-STORAGE 占位行改标、parity-matrix 两行修正、final-gate BotAdapter 引用标注
  15. 工作区清理：服务端 `%SystemDrive%/` 垃圾目录 + runtime/plugins/*.jar.old；插件 .workbuddy-ai/、src/main 内 .bak 文件

## 8. 工作区卫生（仅记录，本次未清理）

- 服务端 `%SystemDrive%/ProgramData/Microsoft/Windows/Caches/`：3 个 Windows 缓存 db，某次脚本把 `%SystemDrive%` 字面量当相对路径所致，与代码无关
- 服务端 `runtime/plugins/*.jar.old` 遗留
- 插件 `.workbuddy-ai/` 目录污染
- 插件 `base/build/libs/base-build 1.jar`（带空格）为旧产物遗留

