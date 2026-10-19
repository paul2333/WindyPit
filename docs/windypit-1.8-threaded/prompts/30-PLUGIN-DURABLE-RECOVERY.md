# Prompt 30 — ThePitUltimate 持久交易、SQLite 单写者与硬崩溃恢复

仓库：C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
分支：thepitultimate-1.8-threaded
前置：Prompt 05 当前工作树基线完成；Prompt 25 已梳理真实交易调用点和 owner API。由 DSH 本身实现，不调用 Claude Code。

## 目标

保证玩家档案、经济、奖励、市场、玩家交易、邮箱和审计的持久写入在真实进程硬退出后可幂等恢复。不能用同进程异常、mock 或内存 transaction map 代替持久恢复。

## 先审计现状

阅读 SqliteDatabase、SqliteStore、SQLite backend、PointLedger、市场/玩家交易/mailbox/reward/audit、当前 CommerceTransactionLog 和 shutdown。为每条变更绘制：请求进入 → owner 状态读取 → 钱/物品预留 → durable 写入 → owner apply → GUI/消息确认。记录同一 UUID 并发写入顺序、数据库连接所有者和停服路径。

## 实施要求

1. 明确哪些操作属于原子事务。transaction ID 固定绑定 canonical request hash、参与者 UUID、物品/货币 delta、profile version 和业务类型；同 ID 不同 request 必须拒绝。
2. 持久状态机包含 NEW、RESERVED、COMMITTED、FAILED、COMPENSATED 或等价且有迁移约束的状态。每个状态转移都使用 SQLite 事务和唯一键保证重放幂等。
3. 不能先在内存中扣币/移物后希望稍后 flush。要么先提交 durable intent/预留，再由 owner apply；要么使用确定性的 write-ahead 方案，在玩家登录/GUI 开启前完成 reconcile。
4. SQLite 使用单写者连接和有界有序队列；同一玩家操作按 UUID 保序；拒绝、重试、deadline、queue depth 和 flush 状态均可见。不得让 Bukkit owner 线程等待 I/O。
5. 恢复时先验证 transaction log 与 profile version，再启用相关玩家经济和 GUI。损坏、重复 item identity 或无法确定归属的数据必须隔离，不可猜测补物品。
6. 将恢复和已接受事务 drain 接入插件 disable 与服务端 stop。队列满时明确拒绝尚未接受的请求；已接受工作只能完成、被持久恢复或明确报失败。
7. 建立独立子进程 harness：父进程启动相同 SQLite 数据库，子进程在预定 fault point 使用 Runtime.halt/等价硬终止；新 JVM 重启并执行真实恢复代码。
8. 至少硬停并重启验证：预留后/最终 commit 前、commit 后/owner apply 前、owner apply 部分完成、重复请求重试、停服 drain 中。每种检查余额、物品 identity/count、ledger 状态、profile version 和交易可重放性。
9. 失败测试必须验证没有重复扣款、增币、物品复制或静默丢失；另测进程正常关闭和数据库损坏隔离。

## 验证

- Microsoft OpenJDK 25.0.3。
- .\gradlew.bat :base:test --offline --console=plain --no-daemon --rerun-tasks
- .\gradlew.bat :base:check --offline --console=plain --no-daemon
- 单独执行子进程 hard-halt harness；记录每个 child exit code、重启日志、SQLite DB 前后 hash 和完整断言结果。
- 在 WindyPit runtime shutdown 流程运行插件 disable + server stop 测试。

只在重启 JVM 后读取同一持久数据库、运行真实 recovery 并验证恰好一次，才可把 crash recovery 标 PASS。

