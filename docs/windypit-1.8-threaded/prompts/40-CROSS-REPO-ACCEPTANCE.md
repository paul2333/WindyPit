# Prompt 40 — 双仓库对抗式验收与发布门禁

执行者：DSH 本身作为 verifier；不得调用 Claude Code。实现者已结束修改或被严格限制在 verifier 指定缺陷文件；先重新记录两仓库工作树，不信任已有报告。

仓库：
- WindyPit：C:\Users\ylxyl\OneDrive\Desktop\WindyPit，windypit-1.8-threaded。
- ThePitUltimate：C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release，thepitultimate-1.8-threaded。

## 验收顺序

1. 记录当前 branch、完整 HEAD、提交列表、git status、diff、未跟踪文件和 SHA-256。检查所有拟发布源文件已纳入提交树；不能以依赖工作树未跟踪文件的构建算 PASS。
2. Microsoft OpenJDK 25.0.3：运行服务端 wrapper -version 与 Gradle --version 并保存原始输出。
3. 从新隔离 checkout 编译并测试两仓库；不触碰或删除现有 worktree。服务端执行 .review\harness\mvn-jdk25.bat -o -B clean install；插件执行 :base:test 和 :base:build。插件全量测试必须连续通过 3 次，且默认 :base:check 不能排除关键测试。
4. 对实际新建 WindSpigot.jar、WindSpigot-API.jar 和 ThePit plugin jar 运行 artifact-boundary-scan，确认没有新引入现代 Folia/Paper/Adventure/DataComponent API；WindyPit 核心不依赖 ProtocolLib。保留 ThePitUltimate 已有 1.8 ProtocolLib 集成，并检查其实际 consumer、协议回归和 SHA-256。
5. 逐条查生产源码：配置是否加载并生效；REGION 是否启动真实 owner 工作；当前世界 tick 是否仍 primary 串行；是否有只创建不消费的 plan/adapter；跨线程 Bukkit/NMS 访问；CallerRuns 错 owner；无界池；已接受 work 静默丢弃；sync DB 热路径；回滚 flag 未读取。
6. 用 target WindSpigot 1.8.8 jar 启动并实际连接协议 47 客户端。验证启动/插件 enable、登录、移动、认证、近战、伤害、击退、弓箭/药水/TNT、死亡/重生、视觉、NPC/机器人、市场、交易、邮箱、保存、断线、插件 disable 和 server stop。Folia 26.x 不替代目标运行时。
7. 证明至少两个独立 owner 在 WindyPit 实际生产工作上以固定命名线程并行，给出线程名、工作 ID、开始/结束时间、结果被消费证据；证明共享竞技场所有战斗结算保持单 owner FIFO。
8. 在生产服务器集成路径中验证正常 shutdown、plugin disable、队列饱和、任务异常和 rollback。队列 drain 必须覆盖已接受持久写入和网络工作。
9. 对真实 SQLite 数据做独立子进程硬停恢复测试，在 Prompt 30 的所有边界验证恰好一次货币/物品和 ledger 状态。
10. 同一目标机器、同一版本/配置/地图/玩家脚本做 baseline 与 optimized 压测：0、20、50、100 玩家以及混合机器人/NPC/视觉/市场/审计/回放；每组先 warmup 5 分钟，测量 10 分钟。没有真实客户端时仅 live gate 为 BLOCKED_EXTERNAL，完成所有内部测试。
11. 每个有效压力场景采集 JFR。对全部 50ms+ event stack 分类：core owner block、DB/file I/O、GC、safepoint、network、intentional pacing 或 unexplained。空闲 JFR 不代表 PvP 性能。
12. 固定门槛不随结果更改：平均 TPS 至少 19.5、tick p99 不高于 50ms、无反复未解释 owner 核心线程 50ms+ 调用栈、无接受后丢失的交易/持久化工作、无玩法差异。20 TPS 另外单独报告。
13. 对照源码和日志更新双方 parity matrix、test-counts、execution-ledger、ACCEPTANCE-VERIFICATION.md 与 final-gate；删除任何源码、运行时证据不支持的 DONE_VERIFIED/SUPPORTED。

## 反向检查清单

- 世界 tick 真正发生在哪些线程？是否仍由主线程遍历？
- 计算结果实际影响什么可观察生产行为？
- Tick/实体是否 exactly-once？跨 owner 是否可能复制、遗漏或双 tick？
- Arena 共享战斗有没有被错误异步化？
- Bukkit/NMS 的事件回调和连接写入是否符合 owner/Netty 约束？
- 适配器实际调用点在哪里？shutdown 和 feature rollback 是否接线？
- 交易 hard halt 是否真实结束 JVM，而不是抛异常？
- 默认测试是否遗漏被排除的 movement/auth tests？
- TPS/JFR 是否来自正在进行的目标 1.8.8 PvP？
- 历史计数、旧 HEAD、旧 jar 是否误作本轮证据？

## 最终报告

两仓库各自写验收报告，提供每个 gate 的 PASS/FAIL/PARTIAL/BLOCKED_EXTERNAL、准确 commit、命令/退出码、JUnit 计数、raw log/recording 路径、SHA-256、剩余问题和下一步。任何必需代码或玩法 gate 未过时禁止使用 RELEASE_READY。所有外部阻塞必须精确描述缺少的资源和复测步骤。

