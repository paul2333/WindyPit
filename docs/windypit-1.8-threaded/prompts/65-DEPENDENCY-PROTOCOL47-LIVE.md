# Prompt 65 — 依赖闭环与真实 1.8.8 协议 47 gameplay gate

仓库：两仓库都可修改，但服务端只修兼容性，不新增现代 API 依赖。前置：Prompt 60。由 DSH 自己执行，不调用 Claude Code。

## 目标

消除 ThePit live smoke 已记录的 Adventure 下载 404、WorldEdit 缺类和停服异常，随后用目标 WindSpigot 1.8.8 运行实际协议 47 客户端。不能用裸服务端 probe 或“插件启用后退出”替代玩法会话。

## 依赖修复

```powershell
cd C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
rg -n --glob "*.java" "PANDA_REPO|repo.panda-lang.org|new Dependency|WorldEdit|com\.sk89q" base\src\main\java base\build.gradle.kts
```

1. 对 Adventure 构件优先使用已锁定且可离线复现的 Gradle/Maven 坐标或仓库；删除失效的 panda-only fallback，保留 checksum/版本清单。干净目录能在无网络时从声明依赖启动；不能把下载失败吞掉后仍标绿。
2. 对 WorldEdit 明确为 required capability 还是 optional event capability。若 ThePit 事件确实需要它，提供与 1.8.8 API 匹配的声明依赖并在 clean server 验证；若允许缺失，启动时注册 capability-disabled event 并使相关 GUI/命令明确拒绝，不能在 tick 中反复抛 `NoClassDefFoundError`。
3. 修复 `HologramRunnable` 的未 schedule cancel race、ProtocolLib disable 的 ConcurrentModificationException 以及所有本轮日志中的真实 ERROR/WARN。没有根因修复和回归测试不能标 PASS。

## 真实客户端路径

使用 target WindSpigot jar、ThePit jar、声明的依赖和一份可复现 world，记录 server/client revision。协议 47 客户端至少执行：登录、认证、移动、近战伤害、击退、弓箭/药水/TNT、死亡/重生、scoreboard/nametag/actionbar/bossbar、NPC、market GUI、trade 或 mailbox、退出、插件 disable、server stop。保存客户端脚本/录制、server stdout/stderr、RCON TPS 和异常。

若当前机器没有可运行的真实 1.8.8 client，先完成所有依赖与内部 integration tests，再把 live client gate 标为 `BLOCKED_EXTERNAL`，明确缺失客户端和复测命令；不得把 protocol probe 标作 gameplay PASS。

## 验收命令

```powershell
cd C:\Users\ylxyl\OneDrive\Desktop\WindyPit
.review\harness\mvn-jdk25.bat -o -B clean install
cd C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
.\gradlew.bat :base:test --offline --console=plain --no-daemon --rerun-tasks
.\gradlew.bat :base:build --offline --console=plain --no-daemon
```

提交真实依赖清单、jar SHA-256、启动/停止记录和 gameplay 结果。任何依赖异常、客户端断线或未测试玩法保持 `PARTIAL/BLOCKED_EXTERNAL`。

