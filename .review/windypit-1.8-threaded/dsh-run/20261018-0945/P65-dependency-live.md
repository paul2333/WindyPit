# Prompt 65 — dependency closure and protocol 47 live gate

Round: `20261018-0945`

## Fixed in this round

| Symptom (20261018-0030 log) | Root cause | Fix | Evidence |
|---|---|---|---|
| `repo.panda-lang.org` Adventure 404 | `Dependency` listed the dead panda repository as the last fallback and downloaded artifacts that are already shaded into the plugin jar | removed `PANDA_REPO`; `Dependency.isBundled()` probes the relocated Adventure/Hutool/annotations classes and skips the runtime download entirely; resolution failures now log `[Dependencies] SEVERE` instead of being swallowed | `P65-smoke-stdout.log`: `PANDA_REPO=False`, `DEPENDENCY_SEVERE=False` |
| `Not scheduled yet` on startup | `NametagThread extends BukkitRunnable` but was scheduled through the WindyPit adapter as a plain `Runnable`; its internal `task` stayed null, so `NametagHandler.setTicks()` threw before creating the replacement | `NametagThread` now implements `Runnable`, owns its `Cancelable`/`BukkitTask`, has an idempotent `cancel()` and tolerates a rejected schedule | `NametagTaskRetirementTest` (2 tests) PASS; smoke `NOT_SCHEDULED_YET=False` before and after stop |
| `HologramRunnable` shutdown warning | `BukkitRunnable.cancel()` throws when the timer never started | own `BukkitTask` handle + idempotent `cancel()` | compile + smoke shutdown has no exception |
| ProtocolLib `ConcurrentModificationException` on disable | async handlers and packet listeners were never unregistered | `PitHook.disable()` calls `unregisterAsyncHandlers(plugin)` and `removePacketListeners(plugin)`; invoked from `ThePit.unregisterExternalHooks()` | smoke `CONCURRENT_MODIFICATION=False` after stop |
| `MythicWellMenu` class-init failure during shutdown/tests | `AnimationRunnable` constructor scheduled itself and NPE'd when the scheduler was unavailable | scheduling wrapped in a guarded try/catch | `WellMaterialRetrievalTest` PASS |
| `Could not save windypit.yml` comment error | `YamlCommenter` threw when a conditional key was absent | missing optional keys are skipped at FINE level; indentation no longer reads `line - 1` when `line == 0` | smoke has zero ERROR/SEVERE |

## WorldEdit capability

`com.windypit.pit.compat.WorldEditCapability` probes `com.sk89q.worldedit.bukkit.BukkitWorld`.
`EventFactory.init` skips the seven WorldEdit-6-dependent events with one explicit WARNING
(`CakeEvent`, `KingOfTheHillEvent`, `KingOfTheLadderEvent`, `HamburgerEvent`, `RaffleEvent`,
`RagePitEvent`, `SpireEvent`) instead of failing class initialisation. `WorldEditCapabilityTest`
covers the declaration and probe caching.

## Clean-server startup (new jars)

Server: `server-smoke-p65` (copy of the 0030 smoke world; not overwriting 20261018-0030)
WindSpigot jar: rebuilt from `windypit-1.8-threaded` + P70 changes
ThePitUltra jar: `ThePitUltra-build-1.jar` from `:base:build`
Plugin config: `windypit.features.combat-plan: true`

Result (`P65-smoke-result.txt`, `P65-smoke-stdout.log`):

```
STARTED=True
DONE=True
NOT_SCHEDULED_YET=False
NO_CLASS_DEF=False
CONCURRENT_MODIFICATION=False
DEPENDENCY_SEVERE=False
PANDA_REPO=False
PLUGIN_ENABLED=True
ADAPTER_MODE=True
RCON_TPS_EXIT=0
RCON_STOP_EXIT=0
STOP_EXITED=True
AFTER_STOP_* all False
```

TPS after startup: `1m=20.02 5m=20.00 15m=20.00`, MSPT `0.68`.
Bootstrap: `mode: BUKKIT_FALLBACK, capability: KERNEL_CLASS_PRESENT_NOT_BOUND,
flags{... combat-plan=true ...}`.
No `ERROR` / `SEVERE` / unhandled exception in the startup or shutdown log.

## Real protocol-47 client gameplay gate

A 1.8.9 client installation exists on this machine, but this round did not execute an attended
or scripted real-client session. The previous round's bare protocol probe is **not** accepted as
gameplay evidence. Therefore:

- Dependency / startup / shutdown sub-gate: **PASS** (evidence above).
- Real client login/move/combat/knockback/projectile/death/reward/GUI sub-gate: **PARTIAL** —
  not executed; no client automation harness exists in the repository, and a manual session was
  not performed in this run.
