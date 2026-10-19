# Acceptance verification — WindyPit — 20261018-0945

Final status: **PARTIAL**

| Gate | Status | Evidence |
|---|---|---|
| Java 25 build + 203 server tests | PASS | `P70-final-test.log` / `P70-clean-install.log`, exit 0 |
| Owner contract for every NMS/Bukkit access class | PARTIAL | `docs/windypit-1.8-threaded/owner-contract.md`; NMS add/remove/chunk/quit/world-change still unasserted |
| Two independent non-arena owners execute consumed work | PARTIAL | 2 world owner domains registered (`world-owner-0/1`); idle counters 0; `MultiWorldProductionConsumptionTest` synthetic |
| Shared arena single owner + FIFO | PASS (scope-limited) | existing `RegionScheduler` FIFO/owner tests + smoke `Arena owner: PRIMARY` |
| World/entity tick parallelism | NOT CLAIMED | `WorldTicker` still calls `doTick()` / `tickEntities()` on primary |
| Tracker plan exactly-once / stale / drain | PASS (scope-limited) | existing tracker tests |
| `windypit.yml` single schema owner | FAIL (open) | still read by `WindyPitConfig` and `WindSpigotConfig` |
| Clean startup / stop | PASS | `P65-smoke-result.txt`: no ERROR/SEVERE, clean stop |
| Load / JFR | NOT PASSED | no 20/50/100 run, no loaded JFR |

Artifacts: `run-index.md`, `execution-ledger.csv`, `P70-owner-tick.md`, `P65-dependency-live.md`,
`P65-smoke-*`, `subagent-p70\`, `evidence-sha256.txt`.

