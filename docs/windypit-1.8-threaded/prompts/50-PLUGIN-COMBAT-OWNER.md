# Prompt 50 — ThePitUltimate 战斗计划真实生产接线

仓库：`C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release`，分支 `thepitultimate-1.8-threaded`。前置：Prompt 45 已刷新基线。由 DSH 自己实现，不调用 Claude Code。

## 目标

把不可变 combat plan/calculator 接入真实 `CombatListener`、`GameEffectListener`、投射物、击退和死亡/奖励路径，同时保持共享竞技场的事件顺序和原有 ThePit 行为。最终 Bukkit/NMS、事件、玩家属性、库存和 potion 修改只能在 arena owner 执行；纯数值计算可以在线程池执行，但必须带玩家 session generation 和战斗序列号。

## 先审计并记录

```powershell
cd C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
rg -n --glob "*.java" "EntityDamage|EntityDamageByEntity|CombatListener|GameEffectListener|Projectile|Knockback|handlePlayerDeath|processKilled|refreshCombatState" base\src\main\java
rg -n --glob "*.java" "CombatDamagePlan|CombatDamageCalculator|combat-calculator|WindyPit.*Combat" base\src\main\java base\src\test\java
```

把每条已有 legacy 分支记录到 `docs` 或本轮 review：近战、暴击、护甲、附魔、perk、连杀、赏金、药水、弓箭/钓鱼竿、TNT、死亡归属、奖励和击退的输入、输出及顺序。不要凭类名猜行为。

## 实施约束

1. 新增接口和 adapter 放在 `base/src/main/java/com/windypit/pit/combat/`；旧包只留最小 bridge。新增 `CombatRuntime`, `CombatSnapshot`, `CombatResolution`, `CombatOwnerExecutor` 或等价类型，接口必须包含 `UUID/sessionGeneration/combatSequence`。
2. 在 owner 事件入口一次读取并缓存装备、附魔、perk、权限和战斗状态，形成 immutable snapshot；不得在计算线程调用 Bukkit、NMS、Player、Profile、配置、数据库或反射。
3. 计算结果只能包含原始数值和明确的 intent：damage、critical、knockback、potion、projectile ownership、death/reward/economy intent。owner 应检查 generation、目标仍在线、combatSequence 未过期后再应用。
4. `windypit.features.combat-plan` 为真实配置开关；关闭时完整回到原 legacy 分支。拒绝、超时、stale result 和 shutdown 必须计数，不能把失败任务在任意 primary 线程重跑。
5. 共享 arena 内禁止并行结算攻击者/受害者的同一序列。若无法证明 region owner，拒绝异步结果并记录 `owner-violation`。
6. 绝不把伤害事件整体异步化；最终伤害事件、速度、背包、死亡、重生、奖励、经济提交和 Bukkit 回调保持 owner thread。

## 必须加入的测试

- `CombatPlanProductionWiringTest`：实际 listener caller 生成 plan、调用 calculator、提交 owner intent；禁止只 new calculator。
- `CombatParityFixtureTest`：使用 legacy fixtures 对至少近战、暴击/护甲、附魔/perk、击退、投射物、死亡/奖励逐项比较，误差和顺序必须明确。
- `CombatGenerationRaceTest`：退出、传送、死亡、重复伤害后旧结果不能修改新 session。
- `CombatRollbackTest`：开关 false 走 legacy，且与开启路径的关键结果一致。
- `CombatNoIoHotPathTest`：调用栈/测试 spy 证明事件热路径无同步数据库、YAML、完整背包扫描和反射。

## 验收命令

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
.\gradlew.bat :base:test --offline --console=plain --no-daemon --rerun-tasks
.\gradlew.bat :base:check --offline --console=plain --no-daemon
.\gradlew.bat :base:build --offline --console=plain --no-daemon
```

记录生产调用链文件/方法、owner 名称、开关、stale/drop 计数和测试 XML。没有真实 caller、owner assertion、回退和 fixture 的 combat row 仍为 `PARTIAL`。

