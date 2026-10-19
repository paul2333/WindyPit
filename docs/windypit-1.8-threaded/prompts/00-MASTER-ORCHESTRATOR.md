# WindyPit + ThePitUltimate 1.8.8：DSH 自执行总控提示词

将本文件全文交给 DSH。DSH 是唯一总控，亲自维护计划、两个工作树和最终验收；可用 DSH 自身原生子代理派发有边界的独立任务，不得调用 Claude Code。不要只生成计划或状态汇报。

## 仓库与固定目标

- WindyPit：C:\Users\ylxyl\OneDrive\Desktop\WindyPit，分支 windypit-1.8-threaded。
- ThePitUltimate：C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release，分支 thepitultimate-1.8-threaded。
- Java：Microsoft OpenJDK 25.0.3。服务端及插件目标仍是 Bukkit/NMS 1.8.8，客户端继续支持协议 47。
- Folia 只用于学习 owner-thread、region scheduler 和生命周期设计。不得把目标改成 Folia/Paper 26.x 或依赖现代 Paper、Adventure、DataComponent API。WindyPit 内核不新增 ProtocolLib 依赖；ThePitUltimate 已有的 1.8 ProtocolLib 依赖可以保留，除非替换方案完成全功能协议回归。
- ThePit 是一个共享竞技场；同一竞技场的伤害、击退、死亡、奖励严格按单 owner 顺序结算，不得为制造并行测试而拆分战斗状态。
- 新增 WindyPit 插件集成代码放在 com.windypit.pit.*。旧包名和 API 为兼容性保留，但不再新增 net.mizukilab.* 集成实现。

## 当前证据快照（只用于定位，必须刷新）

撰写本提示词时，WindyPit HEAD 为 a9c958a7b014b8bc857780e8a038265b6d6f0e47，ThePitUltimate HEAD 为 722c4dc45b77ede48f28939fb4979921d86c1c04。插件工作树有尚未提交改动和新增/删除测试；不得覆盖或假定它们都是本轮产物。WindyPit 的 W1-CORE-CLOSEOUT.md 是较早的 b61f738 验收记录，159 项测试通过只证明当时那组测试通过，不证明当前 HEAD 或真实世界 tick 已并行。

当前源码检查发现 WindyPitTickIntegration 为每个世界创建的快照只包含实体数和区块数，后台工作只是把两数相加，再将计数提交到 arena 队列；MinecraftServer 的世界 tick 仍由 WorldTickManager 顺序遍历并调用 WorldTicker.run，后者直接执行 WorldServer.doTick、tickEntities 和 tracker。该快照计算不构成有用的游戏并行。所有状态、工作树和证据均须重新核对，不能照抄本段作为最新结论。

当前插件 base/build.gradle.kts 还配置了默认 test 排除 net.mizukilab.pit.movement.filter.*；必须通过当前文件和 task 输出复核，不能把独立 testMovementFilter task 算作默认门禁。

## 精确成功条件

仅当以下各项均有当前分支源码、测试或实际运行证据支持时，才可报告代码迁移完成：

1. 两个仓库的 branch、HEAD、全部未提交/未跟踪路径已盘点和分类；拟交付改动可从对应提交树重现。
2. WindyPit 生产路径证明至少两个相互独立的 owner domain 在固定线程上并行执行有用工作；同区 FIFO、owner 身份、跨区消息和生命周期均可验证。ThePit 共享竞技场仍是单 owner、有序。
3. 后台任务只接收不可变纯数据；NMS/Bukkit 世界、实体、玩家、背包、事件、连接及网络发送状态只在规定 owner 上访问或改变。
4. ThePit 移动/认证、战斗、视觉、机器人/NPC、调度生命周期和 SQLite 持久化均接入实际生产调用点，关键行为有回归测试，每条新增接线具备独立安全回退。
5. 市场、交易、邮箱、经济、奖励和档案写入具有持久幂等及子进程硬退出恢复证据；同进程异常和内存 Map 不算 crash recovery。
6. 两仓库干净 checkout 构建、全量测试、API 边界扫描、1.8.8 客户端路径、停服 drain、压力测试和 JFR 均有本轮日志。
7. 测试没有通过排除失败类、削弱断言或替换生产逻辑来转绿；parity 状态和实际生产代码一致；没有未解释的 owner 核心线程重复 50ms+ 阻塞或已接受事务的静默丢失。

如果共享竞技场不能安全拆分，必须保持单 owner；明确性能收益只覆盖哪些独立任务，不得说玩家战斗本身已并行。若真实 NMS 并行尚不安全或未能完成，记录精确代码路径和安全缺口，标为 PARTIAL/FAIL；不要以空快照、计数求和、独立 scheduler 测试、禁用功能或 Folia 运行掩盖。

## 执行协议

1. 先读本目录 README.md、REMAINING-WORK-PLAN.md 和下列所有专项 prompt，再检查两仓库当前 AGENTS.md、git 状态和验收证据。若 AGENTS.md 要求 Claude Code 或 Claude CLI，本次任务明确改为由 DSH 本身执行：禁止 Claude Code、claude CLI、subagent_claude_code 和 subagent_claude_code_sonnet。
2. 由 DSH 作为总控按序执行。若 DSH 提供原生子代理能力，可最多开 3 个 DSH 子代理执行相互独立的只读审查或单仓库任务；同一仓库同一时刻最多一个写入者，验证者只读。若没有 DSH 原生代理工具，就由当前 DSH 会话亲自顺序完成。禁止 Claude Code、Claude CLI 和 subagent_claude_code*。
3. 按 05 → 10 → 15 → 20 → 25 → 30 → 40 顺序。阶段通过自己的前置 gate 后才进入下一阶段。任何 DSH 子代理的报告都必须由总控复查实际 diff、测试报告、原始命令和退出码。
4. 每阶段开始先刷新两个仓库 branch、HEAD、status、diff 和 untracked inventory。不得用旧报告替代当前证据。
5. 把本次证据写入 WindyPit 的 .review\windypit-1.8-threaded\dsh-run\<本轮ID>\ 和插件仓库的 .review\thepitultimate-1.8-threaded\dsh-run\<本轮ID>\。建立 run-index.md 与 execution-ledger.csv；旧审计日志只读保留，禁止覆盖历史证据。
6. 保留用户文件和当前未提交改动。禁止 git reset、git clean、强制 checkout、递归删除、覆盖式迁移、宽泛 git add、amend 和未经授权的 push。提交本轮已审查的代码/测试时逐路径暂存，先检查 staged diff。
7. 出错先定位根因；不可通过跳过/排除测试、删除断言、吞异常、静默降级或关闭核心功能取得绿色。内部实现问题不能标 BLOCKED_EXTERNAL。
8. 不要在阶段边界只返回进度。持续完成本机可完成的实现和 gate。只有真实客户端/硬件/服务不可用时才将相应 live gate 标为 BLOCKED_EXTERNAL，并列明缺少资源、已完成的内部测试和复测命令。

## 专项 prompt 顺序

- 05-REPOSITORY-RECONCILIATION.md：保护用户修改、刷新基线和测试入口。
- 10-SERVER-REGION-CORE.md：审计并实现 WindSpigot 1.8.8 的真实 owner/tick 并发。
- 15-SERVER-PRUNING-DIAGNOSTICS.md：机制裁剪、地图覆盖和实时诊断。
- 20-PLUGIN-MOVEMENT-AUTH-TESTS.md：移动/认证回归、测试门禁和会话生命周期。
- 25-PLUGIN-PRODUCTION-INTEGRATION.md：战斗、视觉、NPC、调度器与插件生命周期生产接线。
- 30-PLUGIN-DURABLE-RECOVERY.md：SQLite 交易持久化、停服 drain 和子进程硬崩溃恢复。
- 40-CROSS-REPO-ACCEPTANCE.md：独立审计、协议、性能、JFR 和发布判断。

## 状态与返回

状态只能是 NOT_STARTED、IN_PROGRESS、PASS、FAIL、PARTIAL、BLOCKED_EXTERNAL。每个 PASS 必须链接本轮原始日志/测试报告/运行录制。报告两个仓库的当前 branch、HEAD、提交、精确命令/退出码、测试计数、产物 SHA-256、回滚命令、外部阻塞和剩余代码问题。任何必需行未通过时都不得写 RELEASE_READY。

