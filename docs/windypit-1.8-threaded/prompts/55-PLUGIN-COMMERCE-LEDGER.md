# Prompt 55 — ThePitUltimate 全 commerce durable ledger 与硬停恢复

仓库：`C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release`，分支 `thepitultimate-1.8-threaded`。前置：Prompt 50 已冻结 owner intent 边界。由 DSH 自己执行，不调用 Claude Code。

## 目标

在现有 market-buy ledger 之外，把玩家交易、邮箱发送/领取、击杀奖励、会员/booster 货币和档案增量写入同一套持久、幂等、单写者事务协议。禁止只增加内存 `CommerceTransactionLog` 或只测试正常返回。

## 盘点命令和文件范围

```powershell
cd C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
rg -n --glob "*.java" "class (Sqlite|SQLite)|PointLedger|MarketManager|trade|Trade|mailbox|Mailbox|reward|Reward|membership|booster|debit\(|credit\(|transfer\(" base\src\main\java
rg -n --glob "*.java" "DurableTransactionLedger|transferDurable|CommerceTransactionLog|transactionId|txId" base\src\main\java base\src\test\java
```

在 `.review\<round-id>\commerce-mutation-map.md` 列出每个操作的 owner 入口、profile/item/coin delta、SQLite 表、当前连接和关闭路径；先完成这张图再改代码。

## 实施协议

1. 统一 durable ledger schema：`tx_id`、`type`、参与者 UUID、canonical request hash、profile versions、reservation、before/after version、state、recovery outcome、created/updated 时间。`tx_id + request_hash` 唯一；同 ID 不同请求必须返回冲突，不得重放。
2. 状态至少为 `NEW -> RESERVED -> COMMITTED`，失败走 `FAILED` 或 `COMPENSATED`。每次迁移在 SQLite 事务内完成，并使用唯一约束、版本条件和 `BEGIN IMMEDIATE`/等价写锁保护跨 JVM claim。
3. 交易、邮箱、奖励和会员/booster 操作复用同一 coordinator；GUI/NPC/击杀代码只生成 owner command，不直接 debit/credit 或修改对方库存。
4. SQLite 只有一个 writer connection；序列化和快照在 owner 外执行，有界队列提交 writer，结果按 UUID 返回 owner。暴露 queue depth、accepted/rejected/retry/failure/drain。
5. DB durable commit 或可确定恢复的 write-ahead intent 必须先于最终 live inventory/profile apply。重启时在 profile/GUI 可用前 reconcile 未完成事务；损坏记录隔离，不猜重复物品归属。
6. `ThePit.onDisable` 和 WindyPit server stop 必须停止接受新事务，drain 已接受事务，超时明确失败并写日志。不能把未完成工作直接丢进内存。

## 必须加入的 crash harness

创建真实 child JVM（不是同 JVM 抛异常）并复用同一个临时 SQLite 文件，在以下 fault point 调用 `Runtime.halt(42)`：

1. reservation 写入后、commit 前；
2. commit 后、owner apply 前；
3. owner apply 部分完成后；
4. 重复请求和不同 request hash；
5. 多 JVM 同一 tx claim；
6. 正常 shutdown drain 中。

父进程重启同一文件，调用真实 recovery，断言 coins、item identity/count、profile version、ledger state 恰好一次；保存 child exit code、DB hash、重启输出和所有断言。

## 验收

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
.\gradlew.bat :base:test --offline --console=plain --no-daemon --rerun-tasks
.\gradlew.bat :base:check --offline --console=plain --no-daemon
```

必须单独运行 child-process harness。只有 trade/mail/reward/membership/booster 都有真实消费者且每个 crash boundary 通过，commerce parity 才能从 `PARTIAL` 变为 `SUPPORTED`。

