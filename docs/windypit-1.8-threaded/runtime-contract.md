# WindyPit 1.8.8 区域运行时契约（runtime-contract）

> 版本：v1 草案（Wave 1 起点；实现中由测试自证并回填）
> 生成：编排者分块生成后合成；实现者据此落地并在证据中回填实测口径

## 1. Owner 规则

## 所有权域

- **server-global 状态**由主线程独占，任何工作线程禁止直接访问或修改
- **world 状态**由该 world 绑定的工作线程独占，其他线程禁止读写
- **region 状态**由 region 所属 world 的工作线程独占，主线程和其他 world 线程禁止访问
- **entity 状态**由 entity 当前所在 region 的所属线程独占，跨 region 移动时必须转移所有权
- **chunk 数据**由加载该 chunk 的 region 所属线程独占，卸载前禁止其他线程访问
- **player connection**由该 player 当前所在 region 的所属线程独占，切换 region 时必须完成所有权转移
- **packet queue**写入端不限线程，读取端只允许 queue 所属的目标线程消费
- **database worker**的查询结果只能由发起查询的原工作线程消费，禁止结果跨线程共享
- **compute result**只能被提交该计算任务的工作线程读取，其他线程禁止访问

## 跨线程通信约束

- 跨 owner 边界的消息必须是 immutable 类型或已完成深拷贝
- 禁止通过消息传递可变引用、指针或共享容器
- 消息体内禁止携带线程局部状态的引用
- 接收线程必须假定消息内容来自不可信域，禁止直接解引用内部指针

## Pit Arena 特殊域

- **Pit arena**实例在全局唯一的有序 owner 域内运行，该域由专用工作线程独占
- Pit arena 内的所有 player 状态归 Pit 线程所有，禁止原 region 线程直接访问
- Pit 结束后 player 状态必须通过消息传递回目标 region 线程，禁止指针回传
- 多个 Pit 实例禁止共享状态，每个 Pit 必须是独立的 owner 域

## 违反检测

- 任何跨 owner 访问必须触发 assertion 失败并立即终止当前操作
- 违反检测日志必须记录：违反类型、owner ID、访问线程 ID、调用栈
- DEBUG 模式下每次状态访问必须验证 owner ID 与当前线程 ID 一致
- RELEASE 模式下关键边界（region 切换、消息传递）必须保留 owner 检查

## 所有权转移协议

- entity 跨 region 移动时，源线程必须序列化完整状态并标记原状态为 invalid
- 目标线程收到转移消息后必须验证状态完整性，失败时拒绝接收并通知源线程
- 转移期间 entity 处于 in-transit 状态，任何线程禁止修改该状态
- 转移完成前源线程必须暂停对该 entity 的 tick，目标线程接收后恢复 tick

## 2. Region 身份与生命周期

```markdown
## 第 2 部分：Region 身份与生命周期

### 2.1 Region 标识与坐标映射
- **Region ID**: 64-bit `regionId = (worldId << 32) | ((rx & 0xFFFF) << 16) | (rz & 0xFFFF)`，唯一标识一个 region
- **坐标映射**: `(worldId, rx, rz) ↔ regionId` 双向可逆，`rx/rz` 为 region 坐标（每 region 覆盖 32×32 chunks）
- **Chunk 归属**: chunk `(cx, cz)` 归属 region `(cx >> 5, cz >> 5)`，归属关系在 chunk 生命周期内不变

### 2.2 Entity 迁移与边界穿越
- **跨 region 移动**: entity 移动跨越 region 边界时触发 `EntityMigrateEvent(from: regionId, to: regionId, entity)`
- **迁移协议**: 源 region 持有写锁直到目标 region 确认接收，保证 entity 不丢失、不重复

