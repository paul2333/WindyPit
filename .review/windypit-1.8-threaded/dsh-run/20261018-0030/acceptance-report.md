# WindyPit acceptance report — DSH run 20261018-0030

Status: **PARTIAL**. No `RELEASE_READY`. The server builds and starts, and a real protocol-47
client exercised the production owner-domain path, but full NMS world/entity tick parallelism is
not implemented and the cross-repo/plugin/live-pressure gates are incomplete.

## Repository

- Branch: `windypit-1.8-threaded`
- HEAD at run start: `a9c958a7b014b8bc857780e8a038265b6d6f0e47`
- This run's commit: `395fe47` (reviewed source/tests/config only; prompt docs, AGENTS.md and
  `.review` evidence remain uncommitted and preserved).
- Build JVM: Microsoft OpenJDK 25.0.3 (Maven 3.9.16)
- Clean checkout of `395fe47` built in `clean-checkout-windypit`: 180 tests, BUILD SUCCESS
  (`P40-clean-checkout-windypit-build-correct.log`), jar SHA-256
  `29BBA792FBBAD78FEA33058089A953A82D77DD42EC33CC2D2DC602CD6D01BF9B`.

## Gate results

| Gate | Status | Evidence |
|---|---|---|
| Repository inventory / user changes preserved | PASS | `branch.txt`, `head.txt`, `status-porcelain.txt`, `diff-worktree.patch`; no reset/clean/checkout |
| JDK 25.0.3 | PASS | `mvn-jdk25.bat -version` (Java 25.0.3, Microsoft) |
| Server build + full tests | PASS | `P10-P15-server-clean-install.log` / final clean install: 180 tests, 0 failures, BUILD SUCCESS |
| API boundary scan | PASS | `P15-boundary-scan.log`: CLEAN both jars, 0 violations |
| Real per-world owner domains exist | PASS | live thread dump: `WindyPit-Region-world-owner-0/1` |
| Production owner work consumed | PARTIAL | live protocol-47 client → `world [world-owner-0]: submitted=1 planned=1 applied=1`; only one world had a viewer |
| Two independent domains doing useful work concurrently in production | PARTIAL | barrier test proves the two-domain execution path; live smoke only exercised one domain with a player |
| Full NMS world/entity tick parallel | FAIL (not attempted) | `WorldTickManager` still sequential; see `phase10-safety-gaps.md` |
| ThePit shared arena single owner | PASS (design kept) | arena remains pumped primary; no combat split |
| Pruning switches at NMS decision points | PASS (internal) | 8 keys wired; `PruningConfigAndWiringTest`; live config loaded REGION/pruning=true |
| Per-world overrides + fail-safe | PASS (internal) | `PruningConfigAndWiringTest` |
| `/windypit-tps` real TPS/MSPT/per-world/owner data | PASS | `P10-windypit-tps.txt` |
| 1.8.8 protocol 47 client path | PARTIAL | login/join/move/inventory click accepted; no full gameplay client |
| Shutdown drain | PASS | live log: `Draining WindyPit scheduler...` → `drained successfully` |
| 20/50/100 player pressure + JFR | NOT_STARTED | no real clients/load harness this run |
| Plugin integration (phase 20/25/30) | PARTIAL / NOT_STARTED | see plugin run; movement gate PASS, production integration partial, durable recovery not started |
| Clean-checkout reproducibility | PASS | commit `395fe47` archived and built in an isolated directory: 180 tests, BUILD SUCCESS (`P40-clean-checkout-windypit-build-correct.log`) |
| No test weakening | PASS | 180 tests; new tests; no excludes added; `BoundedThreadPoolExecutor` exact accepted counter fixed at production |

## Live smoke details

Disposable copy of a 1.8.8 world at `.review/.../server-smoke` (not a deliverable). Server:
WindSpigot.jar built from this worktree, Java 25, `--noconsole`, RCON enabled.

- `P10-live-smoke-stdout.log`: `Loaded windypit.yml: threading.mode=REGION, arena-owner=PRIMARY,
  region-threads=2, pruning=true, arena-worlds=[world]`.
- `P10-protocol47-probe.json`: protocol 47, login success, join, movement, inventory click
  accepted, server packet ids include 0x01/0x02/0x08/0x30/0x32.
- `P10-live-smoke-thread-dump.txt`: both owner threads; one was `runnable` during planning.
- `P10-windypit-tps.txt`: TPS 20.02/20.00/20.00, MSPT 1.50, world owner domains 2,
  `world [world-owner-0] submitted=1 planned=1 applied=1`, per-world tick p50/p95/p99.
- `P10-live-smoke-stdout.log`: graceful stop and scheduler drain. The final smoke was repeated
  with the final rebuilt jar (`P40-live-smoke-final-driver.log`); the same
  `submitted=1 planned=1 applied=1` and clean drain were observed.

## Artifacts

- `WindSpigot-Server/target/WindSpigot.jar` SHA-256
  `50C74119A582339D5A9C38FC207FB74FEBB11D6F34EC0527F079595BA3FB4404`
  (rebuilt after the last source edit; final smoke used this jar).
- `WindSpigot-API/target/WindSpigot-API.jar` SHA-256
  `233DBB227F823A061312AE562028C2B7ABDF3285C49205B1582B02AC6F30DD26`.

## Rollback

1. Set server-root `windypit.yml`:
   `threading.mode: compat`, `rollback.disable-region-scheduler: true`,
   `rollback.disable-async-tracking: true`, restart. No dedicated owner threads are created and
   the vanilla tracker path runs.
2. Code: `git revert 395fe47` (the commit contains only this run's reviewed source/tests/config).
   Prompt docs, `AGENTS.md` and `.review` evidence were never committed; no reset/clean/forced
   checkout/push was used by this run.

## External / remaining blocks

- No real Minecraft 1.8.8 client beyond the protocol probe; no full join/combat/GUI session.
- No 20/50/100-player load, no JFR under combat.
- Full world/entity tick parallelism requires a region ownership refactor (entity/chunk/event/
  packet/persistence), not present.
- ThePit plugin gameplay acceptance belongs to the plugin run and is not satisfied here.
- `WindSpigotConfig` and `WindyPitConfig` share `windypit.yml`; unknown keys survive the
  round-trip, but the file is doubly owned and comments are rewritten. Documented, not fixed.

## Next steps (ordered)

1. Finish phase 30 durable ledger in the plugin.
2. Run a real ThePit plugin + 1.8.8 client session (join/move/combat/market/trade/mailbox/quit/
   disable) on this WindSpigot build.
3. Run 0/20/50/100 pressure and JFR; classify 50ms+ stacks.
4. Commit reviewed paths and repeat a clean-checkout build/test from the commit.
