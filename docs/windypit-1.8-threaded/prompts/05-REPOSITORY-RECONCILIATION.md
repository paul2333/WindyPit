# Prompt 05 — 两仓库当前状态盘点、保护和可复现基线

执行仓库：

- C:\Users\ylxyl\OneDrive\Desktop\WindyPit，目标分支 windypit-1.8-threaded。
- C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release，目标分支 thepitultimate-1.8-threaded。

本 prompt 由 DSH 本身执行；禁止 Claude Code、claude CLI 及 Claude 子代理。它是实现阶段的前置门。先读 WindyPit 的 docs\windypit-1.8-threaded\README.md、REMAINING-WORK-PLAN.md 和 00-MASTER-ORCHESTRATOR.md。

## 目标

为两个当前工作树生成可信基线；保留用户所有改动；确认编译和全量测试到底从哪些文件、哪些测试 task 得到结果。只有完成基线后才开始功能编码。

## 操作

1. 在任何编辑前，分别保存 branch、完整 HEAD、最近 10 条提交、git status --short --untracked-files=all、git diff、git diff --stat、未跟踪文件列表和关键文件 SHA-256。输出放入每仓库本轮 dsh-run 目录。
2. 阅读两个仓库的 AGENTS.md。不得因为其提到 Claude 执行器而调用 Claude；本任务的执行者是 DSH。AGENTS 里其他用户数据保护和文件边界要求继续遵守。
3. 为每个变更路径分类：本轮实现、测试、用户未提交工作、审计/计划文档、构建产物、本机缓存、未知。未知和用户未提交路径不得重命名、删除或覆盖。需要修改与用户当前改动同一文件时，先读取完整 diff，并将必要修改以最小补丁叠加。
4. 在插件仓库重点复核：
   - base/build.gradle.kts 是否把任何 movement/filter 测试排除在默认 test/check 之外；
   - 被删除的 MovementAuthFilterTest 和 MovementAuthFilterIntegrationTest 是否有未提交替代测试；
   - PremiumBadgeTest 修改是否仍真实调用生产方法，或已用 Mockito stub/mock 重写被测行为；
   - PlayerMoveHandler 的未提交变化与 MovementTierBehaviorContractTest 是否同步。
   不准默认认为已有报告反映当前状态。
5. 校验 Java/Maven/Gradle 实际运行 JVM：
   - WindyPit: .review\harness\mvn-jdk25.bat -version
   - 插件: 设置 JAVA_HOME 为 C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot 后运行 .\gradlew.bat --version
   记录完整输出，不能以 shell java 的版本代替。
6. 运行服务端当前模块测试及完整构建：
   - .review\harness\mvn-jdk25.bat -o -B -pl WindSpigot-Server test
   - .review\harness\mvn-jdk25.bat -o -B clean install
7. 插件先运行与当前变更相关的定向测试，再运行默认完整测试：
   - .\gradlew.bat :base:test --offline --console=plain --no-daemon --rerun-tasks
   - 检查 :base:check 的任务图和结果，确认新 movement 测试确实默认执行。
   如全量测试失败，保存报告并修根因；禁止通过 exclude、onlyIf=false、跳过 test task 或降低断言来规避。
8. 比较 HEAD 构建和工作树构建所需文件。识别所有被引用却未跟踪的源文件。不得在未经分类时恢复/删除任何路径。
9. 审计是否有其他进程在改同一仓库；若观察到本轮采样期间 diff/mtime 持续变化，暂停该仓库写入并完成另一个仓库允许的只读工作，不与未知写入者竞争。
10. 仅对分类清楚、属于本轮目标且测试通过的文件提交；逐路径 git add，复核 staged diff。保留 AGENTS.md 和其他本机文件，除非明确属于已授权交付物。

## 必需产物

两个仓库各写 branch-head.txt、status-full.txt、dirty-manifest.csv、test-baseline.md 和 execution-ledger.csv。每条记录包含命令、exit code、测试通过/失败/跳过数、日志路径与 SHA-256、责任人和下一步。

## 通过标准

两个 repo 的当前状态可审计；JDK 25.0.3 有直接证据；所有未提交/未跟踪用户路径均有分类且保留；默认插件 test/check 不隐藏失败；WindyPit 和插件均得到当前 HEAD 与工作树构建测试结果。任何一个默认测试失败均记 FAIL，并在修复后重新跑完整门禁。

