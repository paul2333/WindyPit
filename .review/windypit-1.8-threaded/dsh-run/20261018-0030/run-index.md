# WindyPit DSH run 20261018-0030 — index and status

Executor: DSH (single orchestrator). No Claude Code / claude CLI / Claude subagent was used.
Run started 2026-10-18 00:28 Asia/Hong_Kong. Evidence directory: this folder.
Historical `.review/windypit-1.8-threaded/*` logs are read-only and were not overwritten.

## Repository state at run start (refreshed, not copied from the prompt)

| Repo | Branch | HEAD | Worktree |
|---|---|---|---|
| WindyPit | `windypit-1.8-threaded` | start `a9c958a`; this run's commit `395fe47` | 11 prompt-doc/AGENTS changes remain uncommitted (preserved); reviewed source/tests/config committed |
| ThePitUltimate | `thepitultimate-1.8-threaded` | start `722c4dc`; this run's commits `74a952d`, `729e239` | user `.gitignore`/TAKEOVER-VERDICT/AGENTS/evidence remain uncommitted (preserved) |

JDK: Microsoft OpenJDK 25.0.3 (`mvn-jdk25.bat -version` → Java 25.0.3, vendor Microsoft).
Gradle 9.8.0 reports `Launcher JVM: 25.0.3 (Microsoft)`.

## Phase status (this run only)

| Phase | Status | Evidence / limitation |
|---|---|---|
| 05 Repository reconciliation | PASS | `P05-server-test.log` (159 tests, exit 0), `P05-server-clean-install.log` (exit 0), `P05-base-test-baseline.log` (1503 tests, exit 0), `P05-testMovementFilter-baseline.log` (23 tests, exit 0). Exclusion found in `base/build.gradle.kts` and handed to phase 20. |
| 10 Server region core | PARTIAL | Real per-world owner domains implemented and wired into `WorldTicker`; live WindSpigot 1.8.8 server in REGION mode created `WindyPit-Region-world-owner-0/1`; a real protocol-47 client caused `submitted=1 planned=1 applied=1`; final rebuilt jar repeated the same run. Full NMS world/entity tick remains primary (documented safety gap); two-domain concurrency is proven in `WorldRegionRuntimeTest` with a barrier, not with two live players in two worlds. |
| 15 Pruning + diagnostics | PASS (internal) / live gate partial | All 8 pruning keys now have a real NMS decision point, per-world overrides and fail-safe config parsing; `/windypit-tps` shows real TPS/MSPT, per-world tick p50/p95/p99, owner domains and pipeline segments. Live startup/stop and command output captured; no long-running pressure/JFR yet. |
| 20 Plugin movement/auth gate | IN_PROGRESS (subagent, reviewed by Lead) | Three consecutive default `:base:test` runs: 1545 tests, 0 failures; `:base:check` same; 10× targeted runs. Awaiting final subagent report and Lead diff review. |
| 25 Plugin production integration | NOT_STARTED | Audits show every adapter had zero production consumers; wiring work is queued behind phase 20. |
| 30 Plugin durable recovery | NOT_STARTED | Audit shows no durable transaction IDs/state machine/child-process hard-halt test. |
| 40 Cross-repo acceptance | NOT_STARTED | Requires 20–30 plus clean-checkout, JFR, pressure and client gates. |

## Phase 10 implementation (this run)

New production classes:
- `com.windpvp.windspigot.scheduler.WorldRegionRuntime` — per-world owner domains; immutable
  `TrackerSnapshot` in, immutable `Plan` out; bounded per-world plan queue; stale generation
  drop; owner-thread FIFO; drain.
- `com.windpvp.windspigot.scheduler.NmsTrackerPlanApplier` — applies add/remove plans to the real
  `EntityTracker` on the primary thread.
- `EntityTracker` / `EntityTrackerEntry` — plan-driven visibility mode; movement-only tracker
  step keeps exactly one `track()` call per tick.
- `WorldTicker` — applies the previous plan before the tracker step, submits a fresh snapshot
  after it, and records per-world tick p50/p95/p99.
- `WindyPitTickIntegration` — creates the runtime only in `threading.mode=region` with async
  entity tracking enabled; removes the old count-sum compute jobs; drains owners on shutdown.
- `WindyPitConfig` / `MechanismPruningPlan` — per-mechanism keys + per-world overrides; demo
  worlds now get the resolved plan.

Live evidence:
- `P10-live-smoke-stdout.log` — server started, `Loaded windypit.yml: threading.mode=REGION,
  arena-owner=PRIMARY, region-threads=2, pruning=true, arena-worlds=[world]`.
- `P10-live-smoke-thread-dump.txt` — both `WindyPit-Region-world-owner-0` and `-1` threads.
- `P10-protocol47-probe.json` — protocol 47 login/join/move/inventory click accepted.
- `P10-windypit-tps.txt` — `World owner domains: 2`, `world [world-owner-0]: submitted=1
  planned=1 applied=1`, owner `WindyPit-Region-world-owner-0`; real TPS/MSPT and per-world
  p50/p95/p99.
- `P10-live-smoke-stdout.log` — graceful stop: `Draining WindyPit scheduler...` →
  `WindyPit scheduler drained successfully`.

Known limitations (not hidden):
- `MinecraftServer` still ticks every world sequentially on the server thread; entity/world
  mutation is not parallel. The owner domains currently do real tracker visibility planning
  only. Claiming "player combat is parallel" would be false.
- Only one world had a live viewer in the smoke, so live two-domain concurrent planning is not
  proven; the barrier test proves the two-domain execution path itself.
- The smoke server is disposable and has no ThePit plugin; plugin/gameplay acceptance is phase
  25/40.

## Phase 15 implementation (this run)

- `WindyPitConfig.PruningConfig`: 8 independent keys, `pruning.worlds.<world>.<key>` overrides,
  `pruning.arena-worlds`, unknown/invalid key warnings, fail-safe defaults.
- NMS decision points: weather (`World.p`), natural/hostile spawn, random tick no-player,
  non-arena entity maintenance, villagers, farming (crops/stem/wart/furnace), redstone/hopper
  (scheduled block ticks, wire, torch, hopper).
- `/windypit-tps`: real TPS/MSPT from `MinecraftServer.recentTps/getLastMspt`, per-world tick
  p50/p95/p99 from `WorldTicker`, owner-domain counters, pipeline segments.
- Tests: `PruningConfigAndWiringTest` (6), plus source contracts for every key; 180 server tests
  total, 0 failures.

## Commands / logs (exact)

| Command | Exit | Result | Log |
|---|---|---|---|
| `.review\harness\mvn-jdk25.bat -o -B -pl WindSpigot-Server test` | 0 | 159 tests (baseline) | `P05-server-test.log` |
| `.review\harness\mvn-jdk25.bat -o -B clean install` (baseline) | 0 | BUILD SUCCESS | `P05-server-clean-install.log` |
| `mvn ... -pl WindSpigot-Server test` (after phases 10/15) | 0 | 180 tests, 0 failures | see latest `P10-P15-server-clean-install.log` |
| `.review\harness\mvn-jdk25.bat -o -B clean install` (after phases 10/15) | 0 | 180 tests, BUILD SUCCESS, jar rebuilt | `P10-P15-server-clean-install.log` |
| `artifact-boundary-scan.ps1` | 0 | CLEAN, 0 violations | `P15-boundary-scan.log` |
| live smoke + protocol 47 probe + RCON | 0 | started/joined/stopped cleanly | `P10-live-smoke-*.log`, `P10-protocol47-probe.json`, `P10-windypit-tps.txt` |

Artifacts (final rebuild):
- `WindSpigot-Server/target/WindSpigot.jar` SHA-256 `50C74119A582339D5A9C38FC207FB74FEBB11D6F34EC0527F079595BA3FB4404`
- `WindSpigot-API/target/WindSpigot-API.jar` SHA-256 `233DBB227F823A061312AE562028C2B7ABDF3285C49205B1582B02AC6F30DD26`
- Boundary scan: CLEAN both jars; 34 adventure-key baseline exempt entries; 13 legacy WARN entries
  (`P40-boundary-scan-final.log`).
- Final live smoke with this exact jar: `P40-live-smoke-final-driver.log`,
  `P10-live-smoke-thread-dump.txt`, `P10-windypit-tps.txt` (2 owner threads, world plan
  applied=1, clean drain).

## Rollback

- Runtime: set `threading.mode: compat` (or `rollback.disable-region-scheduler: true` /
  `rollback.disable-async-tracking: true`) in server-root `windypit.yml`, restart. This leaves the
  vanilla sequential world tick and the legacy tracker path; no dedicated owner threads are
  created.
- Code: `git diff` is the complete uncommitted change; no commit was created by this run.
  To restore the exact pre-run tree for the files touched here, use the path list in
  `status-porcelain.txt` with the user's own review; no destructive git command was used.

## Commit and clean-checkout reproducibility

- Reviewed WindyPit source/tests/config committed as `395fe47` (32 paths, 1886 insertions).
- `git archive 395fe47` extracted to `clean-checkout-windypit`; a clean `mvn -o -B clean install`
  was run there (log `P40-clean-checkout-windypit-build-correct.log`).
- No push, no amend, no reset/clean/forced checkout.

## Remaining code issues (current)

1. Full NMS world/entity tick parallelism is not implemented; the safety analysis and exact
   remaining call paths are in `phase10-safety-gaps.md`.
2. Live two-player/two-world concurrent planning evidence is not captured (no second live
   client/dimension switch in this environment).
3. `WindSpigotConfig` and `WindyPitConfig` both read server-root `windypit.yml`; unknown keys
   survive the round-trip but the file has two owners. This is documented, not fixed.
4. The smoke copy of the 61 MB jar and world data is disposable evidence input; it is not part
   of the deliverable source tree.
