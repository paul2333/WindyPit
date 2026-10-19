# WindyPit 1.8 Threaded Architecture

## Overview
WindyPit 1.8.8 is a single-threaded Spigot fork enhanced with a cooperative region-ownership
scheduling kernel. The kernel is built from pure Java classes in
`com.windpvp.windspigot.scheduler` and integrates with the NMS tick loop through a single
seam at `MinecraftServer.B()`.

## Core Components

### Scheduler Kernel (Wave 1)
- **RegionScheduler**: per-region FIFO task queues with owner-thread validation. Two modes:
  - PUMPED_PRIMARY (arena-owner=primary): the server thread drains the arena queue at the tick seam.
  - DEDICATED_THREAD: each region gets one fixed worker thread with a bounded queue.
- **EntityScheduler**: entity-scoped task routing; entities bind to regions, pending tasks
  retire on entity removal.
- **GlobalRegionScheduler**: bounded immediate FIFO + tick-driven delayed/fixed-rate tasks
  on the primary thread (non-region world work).
- **TickPipeline**: five-phase lifecycle (SERVER_CONTROL -> REGION_SNAPSHOT -> ASYNC_COMPUTE ->
  OWNER_COMMIT -> SHUTDOWN_DRAIN) with exactly-once phase execution, always-stop timing,
  and exception rethrow. Supports full ticks and contiguous segments for host integration.
- **WindyPitTickIntegration**: host-side holder wired to MinecraftServer via a single
  `onTickSeam()` call after `worldTickerManager.tick()`.

### Async Compute (Wave 2)
- **ComputePool**: fixed worker threads over a bounded queue. Jobs carry
  regionId/generation/entityId/sequence for staleness detection. Results are delivered on
  the owning region's owner thread; stale results are dropped and counted.
- **TrackerVisibilityPlanner**: pure visibility planning (distance culling, type multipliers,
  low-latency bypass for TNT/arrow/hook/potion, moved/rotated classification).
- **TrackerAsyncEngine**: submits planning to the ComputePool with generation-tagged staleness.
- **NetworkSendQueue**: per-player bounded outbound queues with DROP_OLDEST/REJECT overflow.

### Entity Ownership (Wave 1)
- **EntityOwnershipService**: entity lifecycle (create/destroy/migrate/quit/worldUnload)
  with pending-task retirement and runtime owner assertions.

### Bot Support (Wave 3)
- **BotBudget**: per-region bot count limit with CAS acquire/release.
- **BotDecisionDispatcher**: bot AI decisions routed through ComputePool.
- **WindyPitBotAdapter** (plugin side): lifecycle management wrapping the scheduler adapter.

### Durable State (Wave 4)
- **DurableOperationLog**: append-only txId|type log with idempotent replay.
- **DurableStateRecovery**: crash-recovery validator (double-spend/orphan/unknown-type).
- **CommerceTransactionLog** (plugin side): idempotent transaction state tracking.

### Diagnostics (Wave 4)
- **WindyPitDiagnostics**: aggregated snapshot (TPS, tick percentiles, per-region/compute/
  network reports, thread violations).
- **TickPerformanceMonitor**: existing tick-duration and CPU sampling (bounded ring).

### Configuration (Wave 5)
- **WindyPitConfig**: threading (mode/arena-owner/threads) + features (async toggles) +
  rollback (disable flags) with YamlConfiguration parsing and safe defaults.
- **MechanismPruningPlan**: 8 per-mechanism toggles for non-PvP mechanism pruning.

## Thread Model
- Primary thread: server tick loop, arena region owner (PUMPED_PRIMARY), global scheduler.
- Dedicated threads: one per dedicated region (NPC zones, bot zones, decoration).
- Compute threads: fixed pool for pure computation (planning, bot decisions, damage calc).
- All queues are bounded; overflow is a visible rejection, never silent loss.

## Plugin Integration
ThePit accesses the kernel via `WindyPitSchedulerAdapter` (reflection bridge + Bukkit
fallback). The adapter routes tasks to arena/global/entity contexts; when the kernel is
absent, everything falls back to the Bukkit scheduler (legacy semantics preserved).

