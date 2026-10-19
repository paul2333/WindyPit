# Prompt 25 — ThePitUltimate 生产路径接入 WindyPit owner API

仓库：C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
分支：thepitultimate-1.8-threaded
前置：Prompt 10 已冻结真实 WindyPit 1.8.8 API 与 owner contract；Prompt 20 的移动/测试基线通过。由 DSH 本身执行，不调用 Claude Code。

## 目标

把现有 WindyPit 插件适配器和纯 Java 计划类接入真实 ThePitUltimate 生产代码。只在可证明安全时启用并行；共享竞技场所有 Bukkit/NMS 战斗写入仍在 arena owner 顺序完成。

## 先行盘点

当前报告曾指出 SchedulerAdapter、CombatDamagePlan/Calculator、VisualChangeDetector、BotAdapter 和 CommerceTransactionLog 有代码/测试但缺实际消费方。逐个查本轮 HEAD 和工作树的调用点。具体检查：

- PlayerMoveHandler、CombatListener、GameEffectListener、投射物 listener、死亡/奖励代码。
- ThePit/WindyPitBootstrap 初始化与 onDisable。
- Scoreboard、nametag、actionbar、bossbar、hologram、排行榜。
- PitBotManager、PitBotNpcFactory、NPC/机器人 spawn、tick、damage、despawn。
- 现存 BukkitRunnable/BukkitScheduler 任务，按玩家 owner、arena owner、全局、纯计算、I/O 分类。

## 实施要求

1. 建立能力探测：检查 WindyPit runtime API 版本/能力；不存在时在启动时选 legacy Bukkit 路径。不能仅根据异常类名判断兼容；生产运行中 owner task 被拒绝后不能偷偷转回任意 primary thread 执行。 新增的 WindyPitSchedulerAdapter、WindyPitBootstrap、MovementAuthFilter、CombatDamagePlan/Calculator、VisualChangeDetector、WindyPitBotAdapter、CommerceTransactionLog 等 WindyPit 专属集成组件，若仍在 net.mizukilab.*，迁到 com.windypit.pit.*；为既有调用点保留最小兼容 bridge。不要批量重命名原 ThePit legacy 业务包。
2. 在真实移动 listener 之外分别接入战斗、视觉、NPC/bot 和任务生命周期。每个 adapter 必须有一个列出的生产调用点、owner assertion、状态生命周期、关闭处理、独立回滚开关与行为 fixture。
3. 战斗：从 owner thread 捕获不可变 combat snapshot，必要纯计算调用 CombatDamageCalculator；最终 Bukkit/NMS DamageEvent、属性、击退、PotionEffect、投射物和库存变更仍在 arena owner。保持暴击、护甲、附魔、perk、连杀、赏金、死亡归属和奖励顺序。事件热路径不得同步读取数据库、YAML、反射或完整背包扫描。
4. 视觉：接入 VisualChangeDetector/NearbyPlayerFilter 的真实 scoreboard/nametag/actionbar/bossbar/hologram 更新；只向可见受众发送变化，1.8 客户端限制和原版 packet 顺序必须保留。检测器未被消费就不计为完成。
5. 机器人/NPC：所有真实实体创建、交互、tick、移动、受伤、掉落和销毁只在 entity owner。只把不可变 decision input 交给 bounded compute；结果带 generation 并回到实体 owner。无观察者时可以节流，但不得影响玩家对机器人的战斗归属。
6. 将 Bukkit repeating task 迁移前先分类；不一次性替换所有任务。owner 任务须在 quit/teleport/death/respawn/disable/stop 时取消或 retire，并防旧回调触达新会话。
7. WindyPitBootstrap.shutdown 必须连到 ThePit.onDisable；保证重复 disable 幂等，并记录未完成/拒绝的任务。
8. 每条线的 legacy fallback 都要测试，并使用实际读取到的 windypit.features 开关，不可使用只在测试中的硬编码值。
9. parity matrix 的状态按生产调用证据更新：类存在、构造成功或孤立单测不代表 SUPPORTED。

## 验收

对每条生产接线执行行为 fixture/owner test，并在真正 WindSpigot 1.8.8 server 上完成至少可自动化的 startup、join、movement、combat、projectile、death/reward、visual、NPC、quit、plugin disable。建立完整测试：
- JAVA_HOME 设置 Microsoft JDK 25.0.3。
- .\gradlew.bat :base:test --offline --console=plain --no-daemon --rerun-tasks
- .\gradlew.bat :base:build --offline --console=plain --no-daemon
- WindSpigot 仓库运行 .review\harness\mvn-jdk25.bat -o -B clean install。
- 对生成的实际插件/服务端 jar 执行边界扫描。

记录调用链文件与方法、owner 来源、回滚 flag、行为测试和日志。若任何 gameplay line 只有构件或测试、没有生产 caller，状态仍为 PARTIAL/NOT_STARTED。

