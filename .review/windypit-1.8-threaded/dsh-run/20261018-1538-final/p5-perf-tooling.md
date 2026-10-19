# P5 performance tooling — design, commands and self-check evidence

Round: `20261018-1538-final` (phase 5, final acceptance tooling)
Repository: `C:\Users\ylxyl\OneDrive\Desktop\WindyPit`
Scope: new files under `runtime/tools/` and `runtime/tests/` only. No server Java source was
modified, no commit/reset/clean/push was performed, and `ThePitUltimate-release` was only read
(plugin jar / schema inspection).

## 1. Files added

| File | Purpose |
|---|---|
| `runtime/tools/rcon_sampler.py` | Source-RCON sampler for `/tps` + `/windypit-tps`; writes `metrics.csv` and `metrics-summary.json` |
| `runtime/tools/jfr_classify.py` | JFR duration-event classifier (>= 50 ms) into 8 categories; writes `classification.csv`, `hotspots.csv`, `gate.json` |
| `runtime/tools/verify_ledger.py` | Read-only ThePit SQLite player/points/transaction-ledger report with `--assert-no-reserved` (exit 2) |
| `runtime/tools/gate_eval.py` | Combines metrics + JFR + ledger evidence into PASS/FAIL/PARTIAL, `gate-summary.json`, `gate-report.md` |
| `runtime/tools/pvp_clients.py` | Minimal protocol-47 PvP clients (login, spawn tracking, health/respawn/chat, ATTACK + Animation) |
| `runtime/tools/run_scenario.ps1` | Parameterised scenario orchestration (`perf/<scenario>/`, Java 25 flags, clients, JFR, 30 s sampling, manifest) |
| `runtime/tests/test_rcon_sampler.py` | 7 tests: parsers, aggregation, fake RCON server end-to-end, failure exit |
| `runtime/tests/test_jfr_classify.py` | 10 tests: duration parsing, category rules, fixture classification, CLI |
| `runtime/tests/test_verify_ledger.py` | 10 tests: player/points/ledger report, read-only enforcement, exit 2, `--output` |
| `runtime/tests/test_gate_eval.py` | 11 tests: PASS/FAIL/PARTIAL thresholds, CLI exit codes |
| `runtime/tests/test_pvp_clients.py` | 9 tests: packet parsers, target policies, fake protocol-47 server end-to-end |
| `runtime/tests/test_run_scenario_contract.py` | 6 contract tests for parameters, Java 25 flags, manifest/layout |
| `runtime/tests/fixtures/p5-tps-sample.txt` | Fixed `/tps` sample (with Minecraft colour codes) |
| `runtime/tests/fixtures/p5-windypit-tps-sample.txt` | Fixed `/windypit-tps` sample |
| `runtime/tests/fixtures/p5-jfr-events.json` | Fixed `jfr print --json` document covering all categories |

No third-party dependency was added; all tools use the Python standard library. The existing
`load_clients.py` and `jfr_profile.py` were not modified (only `protocol_probe.py` primitives are
imported, exactly as `load_clients.py` does).

## 2. Design

### 2.1 `rcon_sampler.py`

- Source RCON wire format (little-endian length / request id / type / NUL-terminated UTF-8 body),
  implemented locally; authentication loops until `SERVERDATA_AUTH_RESPONSE` or `-1`.
- Commands are sent without a leading slash (`tps`, `windypit-tps`), matching Minecraft RCON.
- Interval loop defaults to 30 s; `--duration` defaults to 600 s; `--once` performs one sample.
- Parsed `/tps` fields: TPS 5 s/10 s/1 m/5 m/15 m, tick min/median(p50)/p95/p99/max for the
  10 s and 1 m windows, MSPT, Tracking Queue queued/dropped, Async queues (explosion/path)
  queued/capacity/active/rejected, online players, entity/tile counts.
- Parsed `/windypit-tps` fields: TPS 1 m/5 m/15 m + MSPT, threading mode/arena owner, per-region
  scheduled/executed/rejected/retired/maxDepth (aggregated), compute pool
  submitted/executed/failed/rejected/staleDropped/deliveryRejected/queued, world-owner domain
  applied/dropped/rejected/failed totals, per-world submitted/planned/applied/dropped/rejected/
  failed/queue/owner, and per-world tick p50/p95/p99 maxima.
- `metrics.csv`: exactly one row per sample, with an `error` column. A failed sample is recorded,
  printed to stderr, and the process exits 3 after writing the files (never silently swallowed).
- `metrics-summary.json`: for every numeric column `count/mean/min/max/p99_max`.
  `p99_max` is the maximum observed p99 for columns ending `_p99_ms`; for scalar columns it is the
  nearest-rank 99th percentile of samples (`p99_max_kind` states which).
- `--send-command stop` reuses the same client for scenario shutdown.

### 2.2 `jfr_classify.py`

- Accepts an `events.json` from `jfr print --json --stack-depth 64` (UTF-8, UTF-8 BOM or UTF-16)
  or a `.jfr` recording; for `.jfr` it locates `jfr.exe` via `--jfr`, `WINDYPIT_JAVA_HOME`,
  `JAVA_HOME` or `PATH` and exports the event set itself.
- Duration events: `jdk.JavaMonitorEnter`, `JavaMonitorWait`, `ThreadPark`, `SocketRead`,
  `SocketWrite`, `FileRead`, `FileWrite`, `GarbageCollection`, `GCPhasePause`,
  `SafepointBegin`, `SafepointEnd`. Only `duration >= --threshold-ms` (default 50) is classified.
- Category precedence:
  1. `gc` (GarbageCollection/GCPhasePause)
  2. `safepoint` (SafepointBegin/End)
  3. `intentional_tick_pacing` (ThreadPark/MonitorWait with `MinecraftServer.waitUntilNextTick`,
     `waitForTasks`, `IAsyncTaskHandler.managedBlock`)
  4. `intentional_idle` (ThreadPark/MonitorWait in pool queues: `ThreadPoolExecutor.getTask`,
     `ArrayBlockingQueue.poll/take`, `ComputePool.loop`, `RegionThread.loop`, or known idle
     background threads such as `MVStore`, `Common-Cleaner`, `RCON Client`, Netty loops)
  5. `network` (SocketRead/Write, unless the stack is a DB driver)
  6. `db_file_io` (FileRead/Write or DB frames such as `org.sqlite`, `java.sql`, `SqliteDatabase`)
  7. `owner_core_blocking` (thread is `Server thread`, `WindyPit-Region-*` or
     `WindyPit-Compute-*` **and** the stack contains `com.windpvp.windspigot`,
     `net.minecraft.server`, `cn.charlotte.pit`, `net.mizukilab.pit` or `com.windypit.pit`)
  8. `unexplained` (everything else)
- JFR recorder threads (`JFR ...` or `jdk.jfr` top frames) are excluded and counted in
  `excluded_jfr_internal`, because a 20 s recorder wait is not server work.
- `classification.csv` columns: `event_type,category,thread,duration_ms,stack_hash,owner_thread,
  stack_depth,top_frame,start_time`; stack hash is SHA-256 of the top 16 normalized frames.
- `gate.json`: all eight category counts, `unexplained_total`, `unexplained_repeated` (events in
  repeated `(event_type, stack_hash)` groups), `owner_core_50ms_count`,
  `owner_thread_50ms_count` (all categories on owner threads), `execution_sample_count`,
  `hotspots` (top 20), `top_events` (top 10), and an explicit note that
  `ExecutionSample`/`NativeMethodSample` counts are **not** durations.
- `hotspots.csv` is the ExecutionSample-only count table.

### 2.3 `verify_ledger.py`

- Opens `thepit.sqlite` with `file:...?mode=ro` plus `PRAGMA query_only=ON`; never writes.
- ThePit tables store JSON documents: `players(id,data)`, `points(id,data)`,
  `transaction_ledger(id,data)`.
- `--player` matches UUID (with or without dashes) or `playerName`/`lowerName`
  case-insensitively; reports `coins`, `experience`, `kills`, `deaths`, `streak`
  (`streakKills`), plus `highestStreaks`/`prestige`.
- Reports points `balance`, ledger totals by state/type, `kill_reward_reserved`, and the newest
  N rows (`txId/type/state/payload/recoveryOutcome/updatedAt/updatedAtIso/...`).
- `--assert-no-reserved` exits 2 if any ledger row is `RESERVED` (and prints the count).
  Missing DB/table/player is exit 1 with a reason. `--output` writes UTF-8 JSON/text for gate use.

### 2.4 `gate_eval.py`

- Inputs: `metrics-summary.json`, JFR `gate.json`, ledger JSON from `verify_ledger.py`.
- Checks: `tps_1m.mean >= 19.5`; `tick_p99_ms.p99_max <= 50`; `unexplained_repeated == 0`;
  each accepted-work counter (`tracking_dropped`, `async_explosion_rejected`,
  `async_path_rejected`, `region_rejected`, `compute_rejected`, `compute_failed`,
  `compute_stale_dropped`, `compute_delivery_rejected`, `world_rejected`, `world_dropped`,
  `world_failed`) has `max == 0`; `kill_reward_reserved == 0`.
- `PASS` only when every check passes; `FAIL` if any check fails; `PARTIAL` when an input or a
  metric is missing (missing evidence is never a silent pass).
- Writes `gate-summary.json` (overall/counts/checks/reasons/inputs) and `gate-report.md`.
  Exit code 0 for PASS/PARTIAL, 1 for FAIL, 2 for unreadable input.

### 2.5 `pvp_clients.py`

- Reuses `protocol_probe.py` codec/compression primitives (`encode_packet`, `decode_frame`,
  `_read_packet`, `_string`, VarInt helpers) and follows the `load_clients.py` thread-per-client
  shape.
- Protocol flow: handshake (47, next state 2) → Login Start → Set Compression (threshold handled
  on every subsequent packet) → Join Game (entity id) → Player Position (sends 0x04 position and
  0x15 Client Settings) → KeepAlive 0x00 replies → 0x38 Player List (UUID→name) →
  0x0C Spawn Player (entity_id→UUID/name/position) → 0x0D destroy → 0x15/0x18 position tracking →
  0x06 Update Health (health/food/saturation, health-drop and death counters) → 0x07 Respawn
  (replies 0x16 Client Status VarInt(0)) → 0x02 Chat (JSON text).
- Attacks: 0x02 Use Entity (`VarInt target`, `VarInt 1` ATTACK) followed by 0x0A Animation,
  every `--attack-interval-ms` (default 1000). Targets rotate by entity id (`ring`) or use the
  nearest known position (`nearest`).
- Counters per client: `attacks_sent`, `health_drop_events`, `deaths`, `respawns`, `kills`
  (chat kill-message heuristic), `chat_messages`, `targets_known`, `health`, `errors`.
  Total JSON plus `joined`/`errors`; exits non-zero if any client fails to join or reports an
  error. `--chat-line` sends a chat message after Join Game.

### 2.6 `run_scenario.ps1`

- Parameters: `-Scenario`, `-Clients` (load_clients), `-Bots` (pvp_clients), `-UseThePit`,
  `-WarmupSeconds` (default 300), `-MeasureSeconds` (default 600, minimum 300),
  `-SampleIntervalSeconds` (default 30), `-Port`, `-RconPort`, `-RconPassword`, `-Player`,
  `-ThePitBots` (optional `bots.yml` amount), `-KeepDatabase`, `-Force`, `-SkipGate`, plus
  `-RepoRoot`, `-Template`, `-ThePitRoot`, `-JavaHome`, heap sizes.
- Copies the 0945 template (`...\20261018-0945\server-smoke-p65`) into
  `perf/<scenario>/server`, excludes logs and the two jars from the copy, installs the current
  `WindSpigot-Server\target\WindSpigot.jar`, and optionally the newest
  `ThePitUltra-*.jar` from `ThePitUltimate-release\base\build\libs`.
- Updates `server.properties` (`server-port`, `rcon.port`, `rcon.password`, `enable-rcon`,
  `online-mode=false`, `max-players`) with UTF-8 **without BOM**.
- Starts Java 25 with exactly the four required flags:
  `--add-opens java.base/java.lang=ALL-UNNAMED`,
  `--add-opens java.base/java.net=ALL-UNNAMED`,
  `--add-opens java.base/java.nio=ALL-UNNAMED`,
  `--enable-native-access=ALL-UNNAMED`, plus `-Xms/-Xmx`, eula and `-jar WindSpigot.jar --noconsole`.
- Waits for `Done (`, sleeps 1 s, starts clients, warms up, then starts JFR via
  `jfr_profile.py start` (duration = measurement + 180 s) and `rcon_sampler.py` for the
  measurement window. Afterwards it dumps/stops JFR with `jcmd`, waits for clients, stops the
  server over RCON, then runs `jfr_classify.py`, `verify_ledger.py` and `gate_eval.py`.
- Layout: `perf/<scenario>/{server,logs,clients,metrics,jfr}`, `ledger.json`,
  `gate-summary.json`, `gate-report.md`, `manifest.json`.
- `manifest.json`: scenario/status/timestamps, warm-up/measure seconds, client/bot counts,
  ThePit bot count, Java home/args, server jar SHA-256/size, plugin SHA-256/size, combined config
  SHA-256 before start plus the after-run hash (`windypit.yml`, `server.properties`, `spigot.yml`,
  `bukkit.yml`, `paper.yml`, `taco.yml`), template path, git HEAD, gate overall, and relative
  evidence paths.
- Database reset: unless `-KeepDatabase`, `plugins/ThePitUltra/thepit.sqlite` (+ `-wal`/`-shm`)
  is removed from the copied scenario so old ledger rows cannot pollute the kill-reward gate.
- PowerShell 5.1 compatible; comment-based help is available via `Get-Help`.

## 3. Commands and self-check output

### 3.1 Tool self-tests

```powershell
python runtime\tools\rcon_sampler.py --self-test
python runtime\tools\jfr_classify.py --self-test
python runtime\tools\verify_ledger.py --self-test
python runtime\tools\gate_eval.py --self-test
python runtime\tools\pvp_clients.py --self-test
```

Observed:

```text
rcon_sampler self-test OK
jfr_classify self-test OK
verify_ledger self-test OK
gate_eval self-test OK
pvp_clients self-test OK
```

Each tool also supports `--help` (all exit 0):

```text
rcon_sampler.py --help   23 lines
jfr_classify.py --help   20 lines
verify_ledger.py --help  17 lines
gate_eval.py --help      19 lines
pvp_clients.py --help    23 lines
```

`run_scenario.ps1` help and parse check:

```powershell
Get-Help .\runtime\tools\run_scenario.ps1
$tokens=$null; $errors=$null
[System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path 'runtime\tools\run_scenario.ps1'), [ref]$tokens, [ref]$errors) | Out-Null
"parse_errors=$($errors.Count)"
```

Observed: `parse_errors=0`; parameters include `Scenario, Clients, Bots, ThePitBots, UseThePit,
WarmupSeconds, MeasureSeconds, SampleIntervalSeconds, Port, RconPort`.

### 3.2 Unit/integration tests

```powershell
python -m unittest runtime.tests.test_rcon_sampler runtime.tests.test_jfr_classify `
  runtime.tests.test_verify_ledger runtime.tests.test_gate_eval `
  runtime.tests.test_pvp_clients runtime.tests.test_run_scenario_contract -v
```

Observed:

```text
Ran 53 tests in 2.741s

OK
```

Per module:

```text
test_rcon_sampler        Ran 7 tests  OK
test_jfr_classify        Ran 10 tests OK
test_verify_ledger       Ran 10 tests OK
test_gate_eval           Ran 11 tests OK
test_pvp_clients         Ran 9 tests  OK
test_run_scenario_contract Ran 6 tests OK
```

The tests do not need a live server: RCON and protocol-47 servers are fake in-process sockets;
the ledger test creates a temporary SQLite database with the real table schema.

### 3.3 Real JFR classification check

The classifier was run against an existing 100-client capture from the 0945 round (not an
acceptance run, just parser validation):

```powershell
$env:WINDYPIT_JAVA_HOME='C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
python runtime\tools\jfr_classify.py `
  .review\windypit-1.8-threaded\dsh-run\20261018-0945\P80-load-100.jfr `
  --output-dir tmp\jfr100
```

Observed:

```text
classified=12344 unexplained_repeated=20 owner_core_50ms=0
categories={'intentional_tick_pacing': 1, 'intentional_idle': 12322, 'gc': 1,
            'safepoint': 0, 'network': 0, 'db_file_io': 0,
            'owner_core_blocking': 0, 'unexplained': 20}
```

The 20 repeated unexplained events are `JavaMonitorEnter` on Netty threads blocked in
`org.bukkit.plugin.SimplePluginManager.getPlugin` — a real lock-contention finding in that old
capture, not a classifier artefact. The run also confirms that compute/region worker parks
(`ArrayBlockingQueue.poll` in `ComputePool.loop` / `RegionThread.loop`) are classified as
intentional idle rather than owner blocking.

### 3.4 Live RCON sampler smoke (real server)

A disposable copy of the final smoke server was started on `127.0.0.1:8823` / RCON `25578`
(Java 25 flags, empty ThePit DB), then:

```powershell
python runtime\tools\rcon_sampler.py --once --output-dir tmp\p5-live-metrics
python runtime\tools\rcon_sampler.py --send-command stop
```

Observed: `sampler_exit=0`, `stop_exit=0`, server exited cleanly. One CSV row was produced with
`tps_1m=20.0`, `wp_tps_1m=20.02`, `mspt=0.62`, `wp_mspt=6.82`, `tick_p50_ms=0.6`,
`tick_p95_ms=19.7`, `tick_p99_ms=241.6` (startup spike before warm-up), `tracking_dropped=0`,
`compute_rejected=0`, `world_rejected=0`, `world_tick_p99_max_ms=1.74`,
`threading_mode=REGION`, `world_domains=2`. The startup p99 spike is exactly why the acceptance
protocol requires a 5 min warm-up before measurement.

### 3.5 Live PvP client smoke (real server)

Same disposable server:

```powershell
python runtime\tools\pvp_clients.py --clients 2 --seconds 10 --attack-interval-ms 500 `
  --target-policy ring --host 127.0.0.1 --port 8823 --output tmp\p5-pvp-live.json
```

Observed:

```json
{"clients": 2, "joined": 2, "attacks_sent": 38, "health_drop_events": 0,
 "deaths": 0, "respawns": 0, "kills": 0, "errors": 0}
```

Both clients logged in, received 26–29 spawn entities (ThePit NPC bots plus the other client),
and sent Use Entity/Animation packets without protocol errors. Health drops were 0 in this
10 s smoke (spawn-area bots were not damageable in the short window); the packet path itself is
validated.

### 3.6 Gate evaluation wiring check

Synthetic clean metrics (fixture-derived, zero rejected/dropped counters), the JFR `gate.json`
from the idle recording, and the empty live ledger report were combined:

```powershell
python runtime\tools\verify_ledger.py --db <thepit.sqlite> --json --output tmp\p5-integration\ledger.json
python runtime\tools\gate_eval.py --metrics tmp\p5-integration\metrics\metrics-summary.json `
  --jfr-gate tmp\jfrout\gate.json --ledger tmp\p5-integration\ledger.json `
  --output tmp\p5-integration\gate-summary.json --report tmp\p5-integration\gate-report.md
```

Observed:

```text
**Overall: PASS**
15 PASS, 0 FAIL, 0 PARTIAL
```

This validates file wiring only; it is not an acceptance result.

### 3.7 Scenario command examples

```powershell
# 0 clients, bare server
powershell -NoProfile -File runtime\tools\run_scenario.ps1 -Scenario s00-bare -Clients 0 -Bots 0

# 20 load clients, bare server
powershell -NoProfile -File runtime\tools\run_scenario.ps1 -Scenario s20-bare -Clients 20 -Bots 0

# 50 load clients + ThePit
powershell -NoProfile -File runtime\tools\run_scenario.ps1 -Scenario s50-bare -Clients 50 -Bots 0 -UseThePit

# mixed: 50 load clients + 50 PvP clients + 20 ThePit NPC bots
powershell -NoProfile -File runtime\tools\run_scenario.ps1 -Scenario mixed -Clients 50 -Bots 50 `
  -ThePitBots 20 -UseThePit -WarmupSeconds 300 -MeasureSeconds 600
```

Each run writes `perf/<scenario>/manifest.json` and the gate files described above.

## 4. Not implemented / risks

1. **No full 0/20/50/100 + mixed 15-minute acceptance run was executed by this task.** The tools
   are implemented and self-tested; the actual long runs must be executed with `run_scenario.ps1`
   and will take 15+ minutes per scenario. No TPS/p99/JFR acceptance claim is made here.
2. **`pvp_clients` is not a full game client.** It sends attack/animation packets and tracks
   health/respawn/chat, but it does not open GUIs, use items, simulate projectiles, or render.
   The "GUI" and projectile parts of the real PvP gate still need a real client/manual pass or a
   later extension. Kill counting is a chat-message heuristic (`<name> killed <other>`,
   `You killed ...`), not a server-authoritative counter.
3. **`owner_core_50ms_count` is the `owner_core_blocking` category only.** Long `SocketRead/Write`
   or `FileWrite` events on owner threads are reported as `network`/`db_file_io`; the separate
   `owner_thread_50ms_count` counts all categories on owner threads, so both views are available.
4. **`unexplained_repeated` counts events in repeated `(event_type, stack_hash)` groups.** A
   single one-off unexplained event passes the literal gate but remains visible in
   `unexplained_total`; review `gate.json`/`classification.csv` before accepting.
5. **JFR export memory.** A `.jfr` export uses `jfr print --json --stack-depth 64` for all
   selected events (including ExecutionSample) and loads the JSON in memory. A 10-minute
   recording can produce a large document; the 100-client 0945 capture (4.3 MB `.jfr`) took
   ~9.5 s and produced 12,344 classified rows, but very long captures should be monitored for RAM.
6. **`run_scenario.ps1` assumptions:** Windows PowerShell 5.1, `python` on `PATH`, the 0945
   template at its recorded path, a built `WindSpigot.jar`, and (for `-UseThePit`) a built
   `ThePitUltra-*.jar`. `-UseThePit` reads but never writes `ThePitUltimate-release`. The script
   archives an existing `server/` directory only with `-Force`.
7. **Database reset is the default.** `run_scenario.ps1` deletes the copied scenario's
   `thepit.sqlite` (and WAL/SHM) unless `-KeepDatabase` is passed, so a pre-seeded template DB
   cannot leave stale `RESERVED` rows. Use `-KeepDatabase` only for deliberate seeded runs.
8. **Text parsing is tied to current command output.** `rcon_sampler.py` raises a non-zero error
   if `/tps` or `/windypit-tps` no longer contains the expected fields; it never emits a silent
   empty success. `windypit-tps` fields that are `n/a` in COMPAT mode are simply absent.
9. **`accepted.compute_stale_dropped == 0` is strict.** `staleDropped` is included because the
   task lists it with the other accepted-work counters; if stale snapshot drops are considered
   intentional in a future design, the gate list in `gate_eval.py` should be reviewed rather than
   ignored.
10. **`gate_eval` exit code 1 on FAIL** is intentional so CI cannot mistake a failed gate for a
    successful run; `run_scenario.ps1` accepts 0/1 and records `gateOverall` in the manifest.
