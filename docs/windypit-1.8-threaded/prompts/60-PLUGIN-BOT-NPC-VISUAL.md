# Prompt 60 — ThePitUltimate bot/NPC、视觉和任务 owner 接线

仓库：`C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release`，分支 `thepitultimate-1.8-threaded`。前置：Prompt 50/55 已定义战斗 intent 和 durable command。由 DSH 自己执行，不调用 Claude Code。

## 目标

把 bot/NPC 的真实实体生命周期、机器人决策、可见性和视觉频道接到 WindyPit owner API；清理旧 Bukkit timer 生命周期。纯决策可异步，真实实体和玩家发送必须 owner-confined。

## 生产入口盘点

```powershell
cd C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
rg -n --glob "*.java" "PitBotManager|PitBotNpcFactory|PitBotNpc|NpcFactory|spawn|despawn|tick|damage|syncVisibility" base\src\main\java\net\mizukilab\pit\bot base\src\main\java\cn\charlotte\pit
rg -n --glob "*.java" "Scoreboard|Nametag|ActionBar|BossBar|Hologram|VisualChangeDetector|NearbyPlayerFilter|runTaskTimer|BukkitRunnable" base\src\main\java
```

## 实施要求

1. 新增 `com.windypit.pit.bot`、`com.windypit.pit.npc`、`com.windypit.pit.visual` 集成层；旧包保留最小 bridge。每个真实实体有 owner、generation、spawn/despawn state、observer count 和 bounded work budget。
2. 将 `PitBotManager` 拆成 immutable `BotDecisionInput`、`BotDecision`、目标选择、攻击冷却、路径请求和 `BotEntityOwnerAdapter`。路径/决策线程不得触碰 Entity、World、Player、Bukkit event 或 inventory；结果必须 generation-check 后提交实体 owner。
3. NPC 创建、装备、交互、伤害、击退、掉落、销毁只在实际实体 owner；共享 arena bot 战斗与玩家战斗使用同一 ordered owner。无观察者时只能节流纯决策，不能改变玩家战斗归属。
4. 将 `VisualChangeDetector/NearbyPlayerFilter` 接入 scoreboard、nametag、actionbar、bossbar、hologram 五条真实发送路径；缓存 immutable view model，只在内容/受众变化时发包，保留 1.8 packet 顺序。每条路径增加 `windypit.features.visual-change-dedup` 回退。
5. 将已分类的 repeating task 接入 scheduler adapter，记录 handle 并在 quit、teleport、death、respawn、entity despawn、plugin disable、server stop 取消。修复 `Not scheduled yet` 这类取消竞态；取消必须幂等。
6. 不允许用全局 primary fallback 掩盖 owner 拒绝；拒绝、stale、over-budget 和 drop 进入 diagnostics。保留 legacy 开关逐项关闭新路径。

## 测试与验收

- `BotOwnerLifecycleTest`：spawn/tick/damage/knockback/death/despawn 全部在 owner，退出和 generation 失效旧结果。
- `NpcInteractionOwnerTest`：NPC 交互打开 market/mail/settings/admin GUI 的 command 在正确 owner。
- `VisualProductionConsumerTest`：五个频道均调用 detector/filter，未变化不发包，受众变化会补发。
- `TaskRetirementRaceTest`：未 schedule、重复 cancel、quit/disable 并发不会抛异常或泄漏。
- 运行插件完整 test/check/build，并保存真实 WindyPit startup/stop 日志。任何只在 adapter 单测中存在的路径仍为 `PARTIAL`。

