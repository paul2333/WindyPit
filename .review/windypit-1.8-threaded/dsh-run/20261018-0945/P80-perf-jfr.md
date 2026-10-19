# Prompt 80 — performance / JFR evidence

Round: `20261018-0945`
Server: WindSpigot 1.8.8 (protocol 47), rebuilt from `windypit-1.8-threaded`
Plugin: `ThePitUltra-build-1.jar` (combat-plan=true, bots enabled amount=20)
Java: Microsoft OpenJDK 25.0.3.9, `-Xms1G -Xmx2G`
JVM flags required by ProtocolLib 5.3.0: `--add-opens java.base/java.lang=ALL-UNNAMED`,
`java.base/java.net=ALL-UNNAMED`, `java.base/java.nio=ALL-UNNAMED`,
`--enable-native-access=ALL-UNNAMED`.

## Load driver

`runtime/tools/load_clients.py` — real protocol-47 connections: offline login, Join Game,
client settings, position packet every 100 ms, Keep Alive responses. It is movement/network
load, **not** PvP combat: no attacks, projectiles, trades or GUI transactions.

## Scenario results

| Scenario | Clients joined | Moves | Errors | TPS (1m) | Last MSPT at sample | World tick p99 |
|---|---:|---:|---:|---:|---:|---:|
| 0 players (idle) | 0 | 0 | 0 | 20.02 | 0.70 ms | 0.49 ms |
| 20 players | 20/20 | 7,833 | 1 | 20.01 | 3.84 ms | 4.28 ms |
| 50 players | 50/50 | 21,561 | 0 | 20.02 | 9.99 ms | 5.36 ms |
| 100 players | 100/100 | 45,976 | 0 | 20.02 | 2.22 ms | 18.63 ms |

`Last MSPT` is the server's `getLastMspt()` sampled while clients were still connected; it is a
single-tick value, not an average. The world-tick p99 comes from
`WindyPitTpsCommand`'s per-world `tickPercentileMillis(99)`.

## JFR

Each run captured a profile recording (`P80-load-<tag>.jfr`, ~60 s load + 20 s tail; actual
duration is shorter than the 5-minute target and is reported as such). Parsed with
`runtime/tools/jfr_profile.py report ... --threshold-ms 50`.

| Scenario | Server-thread events >= 50 ms | Non-park server-thread | Classification |
|---|---:|---:|---|
| 0 | 0 | 0 | — |
| 20 | 140 | 1 | 139 × `ThreadPark` (tick pacing); 1 × `JavaMonitorWait` in `ComputePool.awaitTermination` during shutdown drain |
| 50 | 0 | 0 | — |
| 100 | 2 | 0 | 2 × `ThreadPark` (tick pacing) |

No `jdk.GCPhasePause`, `jdk.SafepointBegin`, `jdk.FileRead/Write` or `jdk.SocketRead` event above
50 ms appears in any report. No unexplained core-owner 50 ms+ stack was found in these captures.

## Gate

- average TPS >= 19.5: **PASS** for 0/20/50/100 (20.01–20.02).
- tick p99 <= 50 ms: **PASS for the measured world-tick metric** (max 18.63 ms at 100).
- no unexplained core-owner 50 ms+ stack: **PASS for these captures** (only pacing/shutdown
  waits; sampled hotspots such as `TabHandle`, H2/MVStore, `WorldServer.doTick` and the entity
  tracker are candidates, not proven 50 ms blocks).
- 10-minute measurement / 5-minute warmup: **NOT DONE** — runs are ~60 s load.
- PvP/combat, projectile, death/reward, trade/mail GUI under load: **NOT DONE** — driver only
  moves and sends keep-alives.

Final Prompt 80 status: **PARTIAL**. The numeric gates pass for the measured movement load, but
the scenario coverage and duration do not meet the release contract, so `RELEASE_READY` is still
not allowed.
