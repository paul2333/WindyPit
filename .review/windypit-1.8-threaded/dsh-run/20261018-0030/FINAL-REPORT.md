# DSH run 20261018-0030 — final report (WindyPit + ThePitUltimate)

Status: **PARTIAL**. No `RELEASE_READY`. This report links only evidence produced by this run;
historical `.review` logs were read-only and are not used as current proof.

## Repositories

| Repo | Branch | Start HEAD | This run's commits | Worktree now |
|---|---|---|---|---|
| WindyPit | `windypit-1.8-threaded` | `a9c958a7b014b8bc857780e8a038265b6d6f0e47` | `395fe47` | prompt docs, `AGENTS.md`, `.review` evidence uncommitted/preserved |
| ThePitUltimate | `thepitultimate-1.8-threaded` | `722c4dc45b77ede48f28939fb4979921d86c1c04` | `74a952d`, `729e239` | user `.gitignore`, `TAKEOVER-VERDICT.md`, `AGENTS.md`, `.review` evidence uncommitted/preserved |

No reset, clean, forced checkout, amend, or push was used.

## Phase statuses

| Phase | Status | Key evidence |
|---|---|---|
| 05 Repository reconciliation | **PASS** | baseline git/JDK/build/test inventories; user paths classified and preserved; plugin default test exclusion identified |
| 10 Server owner/region core | **PARTIAL** | real per-world owner domains wired into `WorldTicker`; live REGION server created 2 named owner threads; a protocol-47 client caused `submitted=1 planned=1 applied=1`; full NMS world/entity tick remains primary (documented) |
| 15 Pruning + diagnostics | **PASS (internal) / live partial** | all 8 pruning keys at real NMS decision points, per-world overrides, fail-safe config; `/windypit-tps` real TPS/MSPT/per-world p50-p99/owner domains; live startup/stop |
| 20 Plugin movement/auth/default gate | **PASS** | exclusion removed; 3× 1545 tests/0 failures; `:base:check` green; 10× 42 targeted tests; real production pipeline tests; no test weakened |
| 25 Plugin production integration | **PARTIAL** | lifecycle, flags, movement rollback, nametag visual dedup, timer migration wired; live adapter upgraded `BUKKIT_FALLBACK` → `WINDYPIT_KERNEL`; combat/bot/commerce coordinators still un-wired |
| 30 Plugin durable recovery | **PARTIAL** | durable market-buy ledger + startup recovery + real child-JVM `Runtime.halt(42)` at RESERVED/COMMITTED; 1559 tests; only market buy integrated, no multi-JVM claim |
| 40 Cross-repo acceptance | **PARTIAL** | both commits build in clean checkouts; plugin jar boundary scan CLEAN; ThePit-on-WindSpigot live startup/stop; missing real client gameplay/JFR/pressure |

## Exact commands / results

### WindyPit

- `.review\harness\mvn-jdk25.bat -version` → Java 25.0.3, Microsoft, exit 0.
- `mvn -o -B -pl WindSpigot-Server test` → **180 tests, 0 failures**, exit 0.
- `mvn -o -B -pl WindSpigot-Server package` → BUILD SUCCESS, exit 0;
  `WindSpigot.jar` SHA-256 `50C74119A582339D5A9C38FC207FB74FEBB11D6F34EC0527F079595BA3FB4404`.
- `artifact-boundary-scan.ps1` → **CLEAN**, 0 violations; 34 adventure-key baseline exempt;
  13 legacy WARN.
- Clean checkout `git archive 395fe47` → `mvn -o -B clean install` → **180 tests**, BUILD SUCCESS;
  jar SHA-256 `29BBA792FBBAD78FEA33058089A953A82D77DD42EC33CC2D2DC602CD6D01BF9B`.
- Live bare server: REGION config loaded, 2 owner threads
  (`WindyPit-Region-world-owner-0/1`), protocol-47 login/join/move/click accepted,
  `world [world-owner-0]: submitted=1 planned=1 applied=1`, TPS 20.02, MSPT 1.03, clean
  `Draining WindyPit scheduler...` → `drained successfully`.

### ThePitUltimate

- `:base:test --rerun-tasks` → **1559 tests, 0 failures** (247 classes), 3 consecutive runs
  after the final adapter change; `:base:check` green.
- `:base:build` → BUILD SUCCESS; worktree jar SHA-256
  `C74459870C6843CD8FE3670324C82BCE0EF8A23275BDD0DB33FA5CC5E23F241A`.
- Plugin jar boundary scan → **CLEAN**, 10 legacy WARN.
- Clean checkout `git archive 729e239` → `:base:test :base:build` → **1559 tests**, BUILD SUCCESS;
  jar SHA-256 `9990A0E07BADC6878A26C12C7679EA2B85BB40D799BFE33F8A672C6664B955A3`.
- Cross-repo smoke: ThePitUltra enabled on WindSpigot; bootstrap initially `BUKKIT_FALLBACK`
  (plugin enable precedes `DedicatedServer` integration creation), then
  `adapter upgraded to WINDYPIT_KERNEL`; `kernel[qd=0,viol=0,seams=205]`; clean stop.
- Hard halt: child exit 42 after RESERVED and after COMMITTED; parent reopened the same SQLite
  file and proved exactly-once (60/40 after replay; second replay false). Evidence in
  `sub-phase30/hard-halt-evidence.txt`.

## External / remaining blocks

1. **No real 1.8.8 client gameplay**: a protocol-47 probe passes against bare WindyPit, but the
   same probe against ThePit-enabled server closes during VarInt; no join/combat/market/trade/
   NPC/GUI session. Live client gate = `BLOCKED_EXTERNAL`.
2. **ThePit dependency repository**: `repo.panda-lang.org` returns 404 for Adventure artifacts;
   the plugin still enables, but a fully clean deployment is blocked. Missing WorldEdit classes
   cause epic-event init errors (pre-existing plugin dependency gap).
3. **Full NMS world/entity tick parallelism is not implemented.** Owner domains currently do
   real tracker visibility planning; world/entity mutation stays on the primary thread. See
   `phase10-safety-gaps.md`. Do not read "owner domains" as "parallel player combat".
4. **Phase 25 gaps**: combat plan/calculator, bot/NPC owner routing, and the commerce coordinator
   have no production consumer. Phase 30 covers only market buy; trade/mail/rewards/membership/
   boosters are not durable-idempotent. No multi-JVM claim.
5. **No 0/20/50/100 pressure run, no JFR under combat**, no full parity matrix runtime proof.
6. `WindSpigotConfig` and `WindyPitConfig` both read server-root `windypit.yml`; unknown keys
   survive the round-trip but the file has two owners.

## Rollback

- WindyPit runtime: `threading.mode: compat`, `rollback.disable-region-scheduler: true`,
  `rollback.disable-async-tracking: true`, restart.
- WindyPit code: `git revert 395fe47`.
- ThePit runtime: set the relevant `windypit.features.*` flag to `false` and restart; each path
  has a documented Bukkit main-thread legacy fallback.
- ThePit code: `git revert 729e239 74a952d`.

## Evidence locations

- WindyPit: `.review/windypit-1.8-threaded/dsh-run/20261018-0030/`
  (`run-index.md`, `acceptance-report.md`, `phase10-safety-gaps.md`, `cross-repo-live.md`,
  `execution-ledger.csv`, `evidence-sha256.txt`, logs, clean-checkout logs).
- ThePitUltimate: `.review/thepitultimate-1.8-threaded/dsh-run/20261018-0030/`
  (`run-index.md`, `parity-matrix.md`, `execution-ledger.csv`, `sub-phase20/`,
  `sub-phase25/`, `sub-phase30/`, `sub-phase40/`).

**No `RELEASE_READY`:** required rows (real client gameplay, full commerce/crash coverage,
combat/bot wiring, pressure/JFR) are not all green.
