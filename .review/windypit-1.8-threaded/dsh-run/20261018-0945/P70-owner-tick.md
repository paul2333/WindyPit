# Prompt 70 — WindyPit real owner/tick closure (subagent, verified by orchestrator)

Round: `20261018-0945`
Repo: `C:\Users\ylxyl\OneDrive\Desktop\WindyPit`
Branch: `windypit-1.8-threaded`
Base HEAD: `395fe4765d2757b3b567fa2eeabe358cbf12e17c` (unchanged)

## Delivered

- `docs/windypit-1.8-threaded/owner-contract.md`: per-domain owner definitions (server control,
  shared arena, world region, entity, connection, global task, cross-owner message, chunk
  load/unload, quit/transfer/shutdown) and the explicit statement that `WorldTicker` still calls
  `WorldServer.doTick()` / `tickEntities()` on the primary thread.
- New `WorldOwnerRegistry` with monotonic world generations, entity/chunk register/remove/unload,
  player quit, cross-world transfer tickets and owner assertions.
- New `ImmutableOwnerMessage` / `OwnerMessageValidator` / `EntityOwnerMessage`; `RegionMessage`
  and `RegionScheduler.sendImmutable` reject mutable payloads.
- `WorldRegionRuntime` supports re-registration after retirement; `WindyPitTickIntegration`
  wires `worldOwners` and fixes the `arena-owner=dedicated` shutdown pump exception.
- No `CallerRunsPolicy`, no unbounded queue, no primary fallback; full queue throws
  `RejectedTaskException` and the test proves the caller thread does not run the task.

## Verification performed by the orchestrator

```
git -C WindyPit status --porcelain   -> 5 modified + 10 new files, HEAD unchanged
git -C WindyPit diff --stat          -> scheduler 5 files, docs 6 files
P70-full-test-exit-code.txt          -> 0
mvn -o -B clean install              -> BUILD SUCCESS, 203 tests, 0 failures, 0 errors, 0 skipped
```

New tests (from surefire): `WorldOwnerLifecycleTest` 8, `EntityCrossOwnerMessageTest` 5,
`ChunkUnloadOwnerTest` 3, `WorldTickRollbackTest` 5, `MultiWorldProductionConsumptionTest` 2
= 23 new tests, all PASS.

Runtime evidence (`P65-smoke-tps.txt`, `windypit-tps`):
`Threading mode: REGION | Arena owner: PRIMARY`,
`World owner domains: 2` with `world-owner-0` / `world-owner-1` on distinct
`WindyPit-Region-world-owner-*` threads. Counters are 0 in the idle smoke, so this proves the
owners exist and are addressable, not that they executed production work; the production
consumption proof is `MultiWorldProductionConsumptionTest`.

## Still PARTIAL

`WorldServer.doTick()` / `tickEntities()` were **not** moved; `NmsTrackerPlanApplier` still
applies tracker add/remove on the primary thread; NMS `World.addEntity/removeEntity`,
`ChunkProviderServer.unloadChunks()`, `PlayerList` quit and `EntityPlayer` world change have no
runtime owner assertion; `windypit.yml` is still read by both `WindyPitConfig` and
`WindSpigotConfig` with no single schema owner. Tracker planning must not be described as
world/entity tick parallelism.
