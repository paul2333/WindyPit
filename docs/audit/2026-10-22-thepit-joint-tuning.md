# ThePitUltimate × WindyPit 联合调优：核查结论与修复（2026-10-22）

本文件替代本会话早先生成的几份文档（其中的性能百分比从未实测，部分 API 不存在，已删除）。
下文只写从代码、测试、git 历史里能核对的事实；没有实服数据的地方明确标注。

## 一、多线程到底生效了什么

| 路径 | 跑在哪 | 说明 |
|---|---|---|
| tracker 可见性规划（谁看见谁） | world owner 线程 | 服务端 REGION 模式（现为默认）。快照与规划结果在主线程构建/应用 |
| bot AI 决策（每帧切 4 片） | ComputePool worker | 插件经 `ComputeBridge` 反射提交，结果回主线程执行 |
| 侧边栏静态增伤聚合 | ComputePool worker | 同上，按玩家缓存过期时刷新 |
| 世界/实体 tick、发包、战斗、菜单、附魔、经济 | 服务端主线程 | Bukkit 线程模型决定，不是遗漏 |
| arena-owner: dedicated 的工作线程 | 无插件工作 | 修复后插件不再向它投递任何任务，只是一条空闲线程 |

**关键事实**：从 `e4700f0` 起，ComputePool 这条路径一直是"算了但结果全丢"（见下表第一行），
所以此前"bot AI 已多核并行"的结论在线上并不成立。

## 二、bot 回归：根因与修复

| 症状 | 根因 | 修复（插件提交） |
|---|---|---|
| 完全不动 / 不巡逻 / 不追人 | `ComputeBridge` 以 generation=`1L` 提交；服务端 `snapshotGeneration` 每 tick +1，回投判定 `generation >= gen-1` 两 tick 后恒为假 ⇒ 所有结果被判陈旧丢弃，`onOwner` 不执行，`aiInFlight` 闸门卡住 | generation 改为 `Long.MAX_VALUE`（`77e7c9d`） |
| 不受击退 / 不下落 | IDLE 与贴身 ENGAGE 位移为零时跳过 `move()`，而击退、NMS 外力、重力只在 `move()` 里积分 | 每个决策都执行一次物理步进（`77e7c9d`） |
| 仇恨了也追不上 | `CHASE_SPEED_FACTOR`(1.6) 异步改造后无引用，追击退化为巡逻速度 | 引擎持有该常量并用于仇恨追击（`8058bc5`） |
| 被牵走不回家 | `tooFarFromAnchor` 无调用点，`bots.leash-range` 失效 | 采集帧前判定，超出即清仇恨并回锚点（`8058bc5`） |
| 巡逻抽搐、无仇恨也追人 | 主动扫描半径由 `ATTACK_REACH`(2) 变成 `targetRange`(48)；config.yml 对 target-range 的说明是"只用贴身判定" | 恢复贴身扫描，追击只由仇恨驱动（`8058bc5`） |

守卫测试：`ComputeDeliveryContractTest`、`BotPhysicsStepContractTest`、`BotBehaviourParityContractTest`，
以及 `BotDecisionEngineTest` 中的追击速度与"不追 2 格外"用例。

## 三、线程归属修正

- `9a17415`（本会话）把可见性、实体移除、全息回收、活动记录改为按 arena owner 判定 —— 前提错误，
  已 revert（`b0049df`）。dedicated 模式下它们会用 Bukkit `runTask` 无限自我重调度，
  活动记录还会让 arena 线程写主线程的 `HashMap`。
- `e4700f0` 把存档快照与传送检查改为 arena 路由 —— dedicated 下在 arena 线程读活的背包。
  已改回主线程判定（`8fe28bb`）。
- 守卫：`BukkitStateStaysOnMainThreadArchitectureTest`（调度包之外调用 `runOnArena`/`isArenaOwner` 即红，已用探针验证会红）。

## 四、精简

- 服务端删除：`BotBudget`、`BotDecisionDispatcher`、`DurableStateRecovery`（零引用；已核对插件反射引用的 7 个服务端类全部仍在），
  以及 5 个无消费方的配置键（`entity-threads`、`persistence-threads`、`async-visual-projection`、`async-pathfinding`、`async-persistence`）。
- 刻意保留：`ThreadViolationReporter`、`ThreadContext`（`RegionScheduler` 在用）、`ComputePool`（插件反射使用）。
  旧审计 `multithreading-and-leanness-audit.md` 对这三者的"零引用/死代码"结论不成立。
- 插件删除：未注册的 `WindyPitDiagnosticsCommand`（`/tps` 已输出同样信息）与启动诊断横幅（建议有误且与既有日志重复）。

## 五、服务端默认配置

`threading.mode: REGION`（原 COMPAT）、`region-threads: 1`、`arena-owner: PRIMARY`。
owner 线程按已注册世界创建、上限为 `region-threads`；无玩家的世界不提交任务，所以 ThePit 的单世界布局一条线程足够。
`compute-threads` 默认 auto（核数/2）；示例 `windypit.yml` 写 4，对应 bot AI 每帧 4 片。回滚：`mode: compat` 或 `rollback.*`。

## 六、计划条目处置

| 条目 | 处置 | 依据 |
|---|---|---|
| 启动诊断 / 诊断命令 | 改为计数 `delivered=` | 只看 `submitted=` 时，结果全丢也显示正常；新计数出现在 `/tps` 的 Bot AI 行 |
| bot AI 异步 | 已有，修复回投与 4 处行为退化 | 见第二节 |
| 附魔筛选异步 | 不做 | 候选池按 (注册表, 稀有度, 类别) 缓存，稳态命中即零筛选；未命中也只是一次遍历 |
| lore 渲染异步 | 不做 | 结果需同步写回 ItemStack，渲染本身是几次字符串拼接 |
| 调度器热点迁移 | 不做 | PRIMARY 下 arena 仍由主线程泵出，无并行收益；DEDICATED 下迁移 Bukkit 任务即跨线程访问（第三节两类错误正是如此）。逐个核对的 1–2 tick 周期任务（全息、蝙蝠特效、箭矢尾迹、npclib）都直接读写 Bukkit 实体 |
| 战斗伤害异步 | 不做 | `EntityDamageEvent` 必须在事件内同步 `setDamage` |
| 实体 tick 批处理 | bot 已按片并行 | 原版实体 tick 在 NMS 主线程，插件不碰 |
| 粒子预计算 | 不做 | 发粒子是主线程发包，路径计算是常数级三角函数 |
| 服务端快照对象池 (P-02) | 不做 | 跨线程复用快照对象是竞态来源；短命小对象对 G1 很便宜 |
| 序号改 LongAdder (P-04) | 不做 | 任务序号要唯一且单调，LongAdder 不提供 |
| 50ms poll "唤醒延迟" (P-07) | 不成立 | `BlockingQueue.poll(timeout)` 在入队时立即返回，超时只影响退出检查 |
| 快照防御性复制 (P-08) | 不做 | 每 tick 只省几 KB，不值得放宽不可变契约 |

## 七、实服验证（本会话未能执行）

没有启动带插件的服务端，也没有真实客户端，以下需要在服上确认：

1. `/tps` 的 Bot AI 行：`compute{pool=server, ..., submitted=N, delivered=M, ...}`，M 应紧跟 N；M 长期远小于 N 说明结果仍被丢弃。
2. 同一行的"跳帧"不应持续上涨（持续上涨 = 回投闸门卡住）。
3. 游戏内：bot 沿轨迹巡逻；打一只后同群围上来；能被打退；引出 `bots.leash-range`（默认 24 格）后放弃并回锚点；站在 2 格外不会被主动追。
4. 服务端 `/windypit-tps`：`Mode: REGION | Effective: ACTIVE`，有玩家时 world 行的 submitted/planned/applied 递增。

建议（可选）：实服 `windypit.yml` 的 `arena-owner` 改为 `primary`，省一条空闲线程。实服配置没有改动。

## 八、已知未做

- 没有 TPS / MSPT / JFR 实测数据。
- 非仇恨 bot 的物理（击退、重力）按半速率决策节拍积分，与改造前一致（改造前巡逻同样隔帧执行）；被打后整群进入仇恨，物理恢复每 tick。
- `PlayerProfile` 整份序列化 + SHA-256 仍在主线程（前一轮评估：需先做不可变快照）。
