# Prompt 45 — WindyPit/ThePitUltimate 下一轮总控（DSH 自执行）

把本文件全文交给 DSH。本轮由 DSH 自己执行，不调用 Claude Code、Claude CLI 或任何 `subagent_claude_code*` 工具。可以使用 DSH 原生子代理，但同一仓库同一时间只允许一个写入者；所有代理报告必须由总控重新检查 diff、测试报告和原始退出码。

## 固定范围

- WindyPit：`C:\Users\ylxyl\OneDrive\Desktop\WindyPit`，`windypit-1.8-threaded`。
- ThePitUltimate：`C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release`，`thepitultimate-1.8-threaded`。
- 目标仍是 Java 25 + WindSpigot/Bukkit/NMS 1.8.8 + 协议 47。Folia 只作调度设计参考；不要切到 Folia 26.x、现代 Paper API、DataComponent 或给服务端核心新增 ProtocolLib。
- 共享 ThePit 竞技场必须保持单 owner、FIFO、同步 Bukkit/NMS 变更。只能把不可变快照和纯计算移出 owner。
- 新增 WindyPit 集成代码使用 `com.windypit.pit.*`；旧 `net.mizukilab.*` 只保留兼容 bridge，不再新增同类集成实现。
- 保留所有用户修改和未跟踪证据；禁止 `git reset`、`git clean`、强制 checkout、递归删除、宽泛 `git add`、amend 和未经授权 push。

## 当前已核实基线

当前服务端 HEAD 为 `395fe4765d2757b3b567fa2eeabe358cbf12e17c`，插件 HEAD 为 `729e23948cd4238d5f8ce2166236cc10f04fe59d`。本轮命令已得到服务端 180/0、插件 1559/0，详见 `docs/windypit-1.8-threaded/ACCEPTANCE-20261018.md`。这些数字不是未来运行的替代品；开始每个阶段都刷新 branch/HEAD/status/diff/untracked。

## 成功谓词

只有同时满足以下条件，才能报告代码和玩法迁移完成：

1. combat、bot/NPC、visual、trade/mail/reward/membership/booster 都有真实生产 caller、owner assertion、生命周期清理、独立回退开关和行为 fixture。
2. market 之外的经济操作也使用 durable、幂等、可恢复的事务；子进程硬停后同一 SQLite 文件重启不会重复扣款、增币、复制或丢物品。
3. WindyPit 至少两个独立非竞技 owner 在固定线程执行可观察且会被消费的工作；共享竞技场保持单 owner。若完整 NMS world/entity tick 仍不安全，必须保持 PARTIAL，不能把 tracker plan 说成世界并行。
4. 依赖可在干净服务器目录解析；ThePit 真实协议 47 客户端至少完成登录、移动、战斗、击退、投射物、死亡/奖励和一个 GUI 事务。没有客户端或依赖服务器时，完成内部修复后才把该 live gate 标为 BLOCKED_EXTERNAL。
5. 0/20/50/100 和混合负载有 baseline/optimized 记录，JFR 对所有 50ms+ 栈有分类；未解释的核心 owner 阻塞或已接受工作静默丢失时不得发布。

## 不计入完成的结果

- 只有类、构造函数、孤立单测、source contract 或 Bootstrap 初始化。
- 只有裸服务端协议 probe、Folia smoke、空闲 JFR 或 scheduler barrier 测试。
- 只覆盖 market buy 的 ledger 被描述为完整 commerce recovery。
- 只做 tracker 可见性规划被描述为 NMS 世界/实体 tick 并行。
- 启动但带 `NoClassDefFoundError`、依赖 404、停服异常或 VarInt 断开的服务器。
- 通过排除测试、削弱断言、吞异常、无声切回 primary 或关闭核心玩法得到的绿色。

## 执行顺序

按以下顺序执行，每项先读专项 prompt，再实施、测试、记录证据后进入下一项：

1. `50-PLUGIN-COMBAT-OWNER.md`
2. `55-PLUGIN-COMMERCE-LEDGER.md`
3. `60-PLUGIN-BOT-NPC-VISUAL.md`
4. `65-DEPENDENCY-PROTOCOL47-LIVE.md`
5. `70-SERVER-REAL-TICK-OWNER.md`
6. `80-PERF-JFR-RELEASE.md`
7. 回读本文件和 `40-CROSS-REPO-ACCEPTANCE.md`，执行最终对抗式验收。

每轮证据写入新的目录，例如：

```text
WindyPit\.review\windypit-1.8-threaded\dsh-run\<round-id>\
ThePitUltimate-release\.review\thepitultimate-1.8-threaded\dsh-run\<round-id>\
```

不得覆盖 `20261018-0030`。每个目录必须含 `run-index.md`、`execution-ledger.csv`、原始命令输出、状态和 hash。

## 总控停止条件

- 内部缺陷不能标为外部阻塞；先修代码、测试和配置。
- 只有真实客户端、外部仓库或机器资源确实不可用，才能标 `BLOCKED_EXTERNAL`，并写明缺什么、已完成什么、复测命令是什么。
- 一旦发现交易数据不确定、跨 owner Bukkit/NMS 访问、任务被静默丢弃或回滚路径失效，停止扩大压力测试，先修复并重复失败 gate。
- 最终状态只能是 `PASS`、`PARTIAL`、`FAIL` 或 `BLOCKED_EXTERNAL`；有任何必需项未通过时不得使用 `RELEASE_READY`。

