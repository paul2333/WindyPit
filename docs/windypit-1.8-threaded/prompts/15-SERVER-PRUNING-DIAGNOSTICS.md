# Prompt 15 — ThePit 机制裁剪、地图覆盖与真实诊断

仓库：C:\Users\ylxyl\OneDrive\Desktop\WindyPit
分支：windypit-1.8-threaded
前置：Prompt 05 已完成当前基线。Prompt 10 的 owner 安全边界已记录。本 prompt 由 DSH 执行，不调用 Claude Code。

## 目标

把 WindyPitConfig / MechanismPruningPlan 的配置变成可验证的实际 NMS 决策，补地图级覆盖和 ThePit 所需的真实诊断；不破坏 PvP、投射物、药水、传送、地图保护、必要事件和 Bukkit 插件回调。

## 当前需核实的起点

重新查看 DedicatedServer 配置加载、MinecraftServer world 创建、WorldServer 构造和 World/WorldServer 的天气、实体生成、random tick、红石、农业、hopper、熔炉、村民及区块维护分支。历史 Wave 1 验收记录指出地图级覆盖和 redstone/farming NMS 接线没有完成；不要假定该状态仍未变，也不要把 MechanismPruningPlan 存在当作已接线。

## 实施要求

1. 定义全局默认与每个 world/map override 的合并规则；未知键、缺少地图配置、无效值的 fallback 必须稳定且可诊断。明确配置是在启动时读取还是安全热重载。
2. 每个裁剪开关必须在真正决定是否运行的 NMS 点读取一次有效策略；不以 plugin listener 拦截代替内核减少工作。
3. 配置键按机制明确区分天气、昼夜、生物生成、无关 AI、农业/random tick、熔炉、村民、redstone、hopper、液体等。不要把语义不相干的行为绑到一个总开关。
4. 每个 world override 都按 world 名称或稳定 ID 生效，并有全局默认、继承和显式覆盖测试。
5. 永久保留竞技必要项：玩家 PvP、近战/远程伤害事件、击退、TNT/箭/钓鱼钩/药水生命周期、传送、地图保护及其必要事件。任何“裁剪”不得吞掉这些事件。
6. 策略不可用或配置损坏时 fail-safe 到现有 vanilla/legacy 行为；记录一次有节流的配置诊断，不可默认删除世界机制。
7. 更新 /windypit-tps 或等价运维接口，使其显示真实 TPS/tick p50/p95/p99、CPU/内存、实体与区块数、owner/compute 线程、queue depth/high-water/rejections/stale、每 tick 各世界耗时、tracker/network 和停服 drain。未实现的数据标 N/A，不得造数。
8. 证明采样不会增加每 tick O(all entities) 无收益遍历；实体统计应复用已有计数或有界采样。

## 验收

为每个机制和 override 写 World/NMS 决策点测试，验证 on/off、继承、地图覆盖和 fail-safe。用真实 server config 启动/加载测试。检查 PvP fixtures 确认全部保留。

执行：
- .review\harness\mvn-jdk25.bat -o -B -pl WindSpigot-Server test
- .review\harness\mvn-jdk25.bat -o -B clean install
- 对实际 jar 运行 .review\harness\artifact-boundary-scan.ps1

输出逐键矩阵：配置键、NMS 调用点、默认值、world override、测试名/结果、不可裁剪的相邻行为。把日志和 jar SHA-256 写入本轮 ledger。存在未接线项时标 PARTIAL，不能只因 unit test 通过而标 PASS。

