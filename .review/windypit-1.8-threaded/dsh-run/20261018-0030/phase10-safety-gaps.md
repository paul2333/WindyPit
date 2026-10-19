# Phase 10 — what is actually parallel and what is not (current source)

This file records the exact remaining safety gaps so a PARTIAL result cannot be mistaken for a
threaded world tick. All line references are current working-tree source at run 20261018-0030.

## Current production tick chain (still sequential)

```
MinecraftServer.run()
  -> MinecraftServer.A()/B()
     -> WorldTickManager.tick()                       (primary thread)
        -> for each MinecraftServer.worlds: WorldTicker.run()
           -> WorldServer.doTick()                    (primary)
           -> WorldServer.tickEntities()              (primary)
           -> WorldRegionRuntime.applyPending(world)  (primary; applies previous plan)
           -> WorldTrackerTick.run(this)
              -> EntityTracker.updatePlayers()
                 -> EntityTrackerEntry.updateMovementOnly() when plan-driven
           -> WorldRegionRuntime.submitWorld(world)   (primary builds immutable snapshot)
              -> owner thread: TrackerVisibilityPlanner.plan(...)
           -> explosionDensityCache.clear()
     -> WindyPitTickIntegration.onTickSeam()          (primary)
        -> globalScheduler.tick()
        -> pipeline REGION_SNAPSHOT..OWNER_COMMIT
           -> regionScheduler.pumpOnce(arena)
     -> player packet flush, PlayerList.tick, tickables
```

The world loop is a plain sequential `for` in `WorldTickManager.tickWorlds()`. No
`WorldServer.doTick`/`tickEntities` call is dispatched to a worker. The dedicated owner threads
currently execute only pure visibility planning against immutable snapshots; all NMS mutation
(including applying the plan) runs on the primary thread.

## Why the world/entity tick is not safely parallelised here

1. **One global Bukkit event thread contract.** Entity ticks, block ticks, AI, damage, inventory
   and plugin listeners assume the server thread. Dispatching them on owner threads would fire
   `EntityDamageEvent`, `BlockBreakEvent`, etc. from arbitrary threads and break every plugin
   that calls Bukkit APIs without an owner assertion.
2. **Shared MinecraftServer state.** `MinecraftServer.currentTick`, `recentTps`, `worlds`,
   `PlayerList`, scoreboard, user cache, `server.getScheduler()`, `processQueue` and the
   `TickPerformanceMonitor` are global and unsynchronised. They are read/written from
   `WorldServer.doTick` indirectly (time, weather, spawn, scoreboard, commands).
3. **Entity identity and cross-world moves.** `Entity.id`, `Entity.valid`, `World.entityList`,
   `World.players`, `EntityTracker.trackedEntities`, chunk tickets and `Entity.changeDimension`
   are not partitioned. A player or projectile crossing a world boundary during a parallel tick
   would require generation-checked handoff; no such handoff exists.
4. **Chunk/lighting/persistence.** `ChunkProviderServer`, `ChunkRegionLoader`, `FileIOThread` and
   the region file locks are shared per world; chunk load/unload/save and lighting are not
   thread-confined. `WorldServer.doTick` performs scheduled block ticks, chunk unload, random
   ticks, village/portal work and `processChunkGC` in one call.
5. **Packet ordering.** `EntityTrackerEntry.track()` writes movement/velocity/head-rotation
   packets into per-connection queues (`PlayerConnection.queuePacket(..., trackerThread)`).
   Parallel world ticks would interleave packets for one connection without a defined order.
6. **ThePit shared arena.** The arena is deliberately one ordered owner. Splitting combat,
   knockback, death or reward settlement across owners is forbidden by the acceptance contract.

## What is safely parallel now (useful, consumed)

- `WorldRegionRuntime` creates one fixed owner thread per configured region budget (default
  `threading.region-threads`; 2 in the acceptance profile).
- On every world tick with players, the primary thread builds an immutable
  `TrackerSnapshot` (entity/viewer positions, tracking range, low-latency flag) and submits it to
  the world's owner domain.
- The owner thread runs the real `TrackerVisibilityPlanner` (add/remove/moved/rotated/
  low-latency) and publishes one bounded plan.
- The primary thread applies the plan to the live `EntityTracker` before the tracker step and
  then runs only movement/velocity packet work. A dropped or stale plan forces a full resync.
- Shutdown stops acceptance, drains each owner FIFO and joins the thread; failure to drain is an
  exception, not a silent success.

This is a real reduction of primary-thread visibility planning work, but it is **not** parallel
world or entity ticking and must not be described as such.

## Evidence for the partial claim

- `P10-live-smoke-thread-dump.txt`: `WindyPit-Region-world-owner-0/1` exist on a live
  WindSpigot 1.8.8 server.
- `P10-protocol47-probe.json`: a protocol-47 client joins, moves and performs an inventory click.
- `P10-windypit-tps.txt`: `world [world-owner-0]: submitted=1 planned=1 applied=1`, owner
  thread name, TPS 20.02, MSPT 1.50, per-world tick percentiles.
- `WorldRegionRuntimeTest.twoWorldDomainsPlanConcurrentlyOnDistinctFixedThreads` uses a
  `CyclicBarrier(2)` inside the planning function: the test fails if both owner threads are not
  inside planning at the same time, and then asserts the results are consumed through the real
  applier path.
- `WorldRegionWiringSourceContractTest` pins the production seam order (apply before tracker,
  submit after tracker, exactly once) and the movement-only tracker path.

## What a future full-parallel change would require

A region/owner refactor of `WorldServer` (entity/chunk/block ownership), per-owner Bukkit event
dispatch, generation-checked cross-region handoff, per-connection ordered packet lanes, a
per-region persistence/drain protocol and plugin API owner assertions. None of that is present
or attempted in this run. Until it is, `threading.mode=region` means "parallel visibility
planning", not "parallel game tick".
