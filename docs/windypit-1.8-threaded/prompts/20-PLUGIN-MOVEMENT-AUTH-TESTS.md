# Prompt 20 — 插件移动/认证回归、默认测试门禁和 session 生命周期

仓库：C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release
分支：thepitultimate-1.8-threaded
执行者：DSH 本身；禁止 Claude Code、claude CLI 和 Claude 专用子代理。
前置：Prompt 05 已保存当前插件工作树基线；不要覆盖任何已有未提交修改。

## 目标

使 MovementAuthFilter 接线在真实移动、认证和会话生命周期中保持 legacy 行为，并确保相关测试始终由正常 test/check task 执行。

## 重点文件与核查

从当前源码确认这些位置与实际差异，不要相信旧行号：
- base/src/main/java/net/mizukilab/pit/movement/PlayerMoveHandler.java
- base/src/main/java/net/mizukilab/pit/movement/MovementAuthFilter.java
- base/src/main/java/cn/charlotte/pit/ThePit.java
- 登录/档案加载、认证、退出、死亡、重生、传送相关 listener 和 runnable。
- base/build.gradle.kts 及 movement/filter 下全部测试。
- 旧测试被删除而新测试新增的原因、断言强度和真实生产调用覆盖。

## 必须保持的行为

1. SKIP_NOOP 和 YAW_PITCH_ONLY 只能跳过不需要的昂贵区块/菜单判断；每次 legacy move event 必须继续执行对玩法必要的时间戳、backing 返回取消、slime/launch、边界、反作弊和 perk 状态变更。避免重复应用或双重弹射。
2. 未认证或 profile 未加载玩家仍走现有 AuthGuard/档案门禁；filter session 状态必须由真实 login/profile/auth success/failure/quit lifecycle 更新，不能只在 JOIN 写一个默认值。
3. 登录、profile reload、认证失败、退出、传送、死亡、重生和同 UUID 快速重连的 generation/retirement 语义必须正确。旧异步回调不得覆盖新 session。
4. Teleport 可能由多个既有事件路径重复触发。增加幂等行为测试，不能因第二次 checkMove 短路跳过玩法副作用。
5. 只在明确的兼容开关启用 filter；关闭时执行完整 legacy path。配置和开关必须被生产读取，不能只创建常量或测试 fixture。
6. 新增插件集成实现继续使用 com.windypit.pit.* namespace；已有 legacy 类可继续留在旧包中以保持兼容。 MovementAuthFilter 属于新加的集成组件；若当前仍位于 net.mizukilab.*，将其迁入 com.windypit.pit.movement 并提供最小兼容桥接，禁止在旧 namespace 继续扩展新实现。

## 测试设计

- 对真实 PlayerMoveHandler 方法/明确抽取的生产依赖写行为测试。不能以反射仅检查 helper 名称存在代替副作用断言。
- 断言 same-block/no-op、yaw/pitch-only、block crossing、teleport duplicate、slime/onGround、backing、AntiAFK timestamp、未认证、profile not ready、generation stale、quit/rejoin。
- 对关键 side-effect 使用可观察调用或窄接口 fake，不能 mock 被测方法本身来制造 expected result。
- 检查 PremiumBadgeTest 等同一 suite 修改，确保测试仍调用真实生产方法，不是用 mockAnswer 重复拼装期望行为。
- 所有测试必须参加默认 :base:test 与 :base:check。不得对 movement/filter 测试设置 excludes、onlyIf、禁用 task 或依赖单独手动运行。

## 验证

设置 JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot，检查 .\gradlew.bat --version 的 JVM。
先运行 movement/filter 定向测试并保存失败/通过证据，再运行：
- .\gradlew.bat :base:test --offline --console=plain --no-daemon --rerun-tasks
- .\gradlew.bat :base:check --offline --console=plain --no-daemon

至少运行关键移动/认证测试 10 次新测试进程，并运行全量套件 3 次连续通过；不要把 UP-TO-DATE 当作一次新运行，不要在失败后继续计作连续通过。逐次记录退出码、JUnit XML 总数、日志 SHA-256。每个失败先找根因，不准排除测试来隐藏 suite 污染。

