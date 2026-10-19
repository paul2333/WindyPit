# WindyPit acceptance verification — 20261018-1538-final

Baseline: branch `windypit-1.8-threaded`, HEAD `395fe4765d2757b3b567fa2eeabe358cbf12e17c`
(working tree carries the round's changes; no commit/push performed).

Final status: **PARTIAL**

| # | Gate | Status | Evidence (this round) |
|---|---|---|---|
| 1 | Java 25 build + full server tests | PASS | `p3-final-install.log`: `Tests run: 207, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS; jar `WindSpigot.jar` SHA-256 `BE593B0F6655399513D50F8813995E260F99FF52A11C10A30785D33E52062EE9` |
| 2 | Owner contract per NMS/Bukkit domain | PARTIAL | `owner-contract.md` audit (this round, read-only agent): server-control enforced by `AsyncCatcher` at 16 production call sites; world-region tracker planning runs on dedicated `WindyPit-Region-world-owner-N` threads; entity/chunk/quit/transfer `WorldOwnerRegistry` seams exist but have **no production callers**; cross-owner `sendImmutable` validator exists with **no production traffic**; shared-arena queue has **no production submitter** (pumped but empty). Not claimed as fully wired. |
| 3 | Two independent non-arena owners execute consumed production work | PARTIAL | REGION smoke: `World owner domains: 2`, `world_nether → world-owner-1 (WindyPit-Region-world-owner-1)`, `world_the_end → world-owner-0 (WindyPit-Region-world-owner-0)`, `world → world-owner-0`; counters `submitted/planned/applied` were 0 because no player was in those worlds during the smoke (client/stress phase cancelled by user). Unit-level proof exists (`MultiWorldProductionConsumptionTest`), but no this-round production-load evidence. |
| 4 | Shared arena single owner + FIFO | PASS (scope-limited) | `RegionSchedulerFifoTest` (in 207); smoke `Arena owner thread: Server thread`; arena queue depth 0 at shutdown |
| 5 | World/entity tick parallelism | NOT CLAIMED | `MinecraftServer.java:1117 → WorldTickManager.java:31-34 (sequential for-loop) → WorldTicker.java:69 doTick / :85 tickEntities` — still on `Server thread`. Reported as not migrated. |
| 6 | `windypit.yml` single schema owner | PASS | `WindSpigotConfig` is the only physical reader/writer (load + `copyDefaults` save-back, `WindSpigotConfig.java:43-103`); `WindyPitConfig.fromConfiguration(ConfigurationSection)` derives the WindyPit section from that same in-memory document (`WindyPitConfig.java:226-237`); `DedicatedServer.java:198-220` no longer re-reads the file. New `WindyPitConfigSingleOwnerTest` (4 tests): section derived without second read, unknown keys round-trip, legacy `windspigot.yml` values preserved, missing file → defaults. Bundled template now documents `threading/features/rollback/pruning`. |
| 7 | Bounded pools + visible rejection | PASS (code audit) | RegionThread `ArrayBlockingQueue` + `RejectedTaskException`; ComputePool 1024 + reject counter; GlobalRegionScheduler 4096 + capacity check; CraftScheduler 4096 + AbortPolicy caught/counted; no `CallerRunsPolicy` anywhere in the windypit scheduler. Unbounded queues remain only in non-windypit vanilla paths (statistics opt-in, `ChunkIOExecutor`, vanilla network queues) — listed as allowlist. |
| 8 | Shutdown drain | PASS (code + live smoke) | `MinecraftServer.stop() → windyTick.shutdownDrain(30000)` before plugin disable/save (`MinecraftServer.java:527-540`); pipeline → world regions join → plan-driven off → compute discard → region drain → global inline drain → compute await. Live smoke: `Draining WindyPit scheduler... drained successfully`, clean stop. Documented gaps: CraftScheduler async pool, FastNetworkManager residual frames, ChunkIOExecutor have no explicit shutdown. |
| 9 | Clean startup / shutdown | PASS | `P65b-smoke-result.txt` (twice, incl. final jar): `STARTED=True DONE=True PLUGIN_ENABLED=True ADAPTER_MODE=True REGION_MODE=True`, no `Not scheduled yet` / `NoClassDefFoundError` / `ConcurrentModificationException` / `[Dependencies] SEVERE` / panda repo, RCON `windypit-tps` + `stop` exit 0, `STOP_EXITED=True`, all AFTER_STOP checks false. |
| 10 | 0/20/50/100 + mixed long stress with JFR | NOT RUN | Cancelled by the user for this round (code-only scope). No TPS/p99/JFR claims are made from this round. |

## Corrections of earlier conclusions

- 0945 said `windypit.yml` was still dual-read/write (FAIL). It is now single-owner: `WindSpigotConfig` reads+writes once, `WindyPitConfig` parses the same in-memory document; verified by 4 new tests.
- 0945 said "2 world owner domains registered" without qualification. This round confirms the domains exist and are bound to distinct `WindyPit-Region-world-owner-N` threads, but their production counters are 0 without players in those worlds — the claim is scoped to registration, not to consumed work.

## Not claimed

- `WorldServer.doTick()` / `tickEntities()` are **not** parallel; no Folia-style world tick is claimed.
- No release-ready claim: live gameplay, long stress and JFR gates were not executed this round.


