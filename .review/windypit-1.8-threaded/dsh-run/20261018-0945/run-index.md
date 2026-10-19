# Run index — WindyPit / windypit-1.8-threaded / 20261018-0945

- Round: `20261018-0945` (does not overwrite `20261018-0030`)
- Repo: `C:\Users\ylxyl\OneDrive\Desktop\WindyPit`
- Branch: `windypit-1.8-threaded`
- HEAD at start and end: `395fe4765d2757b3b567fa2eeabe358cbf12e17c`
- Java: Microsoft OpenJDK 25.0.3.9
- Build: `.review\harness\mvn-jdk25.bat` (offline)

## Phases

| Phase | Command | Exit | Result |
|---|---|---|---|
| baseline | `git rev-parse HEAD`, `git status --porcelain` | 0 | captured in `baseline-*.txt` |
| P70 owner/tick | `mvn -o -B -pl WindSpigot-Server test` | 0 | 203 tests, 0 failures, 0 errors, 0 skipped |
| P70 package | `mvn -o -B clean install` | 0 | `WindSpigot.jar` rebuilt, 203 tests |
| P65 rebuild | `mvn -o -B -pl WindSpigot-Server package -DskipTests` | 0 | `WindSpigot.jar` |
| P65 smoke | `P65-run-smoke.ps1` (clean server copy, new jars, RCON stop) | 0 | `Done`, plugin enabled, TPS 20.02, clean stop |
| P65 smoke (final) | same script after YamlCommenter fix | 0 | no ERROR/SEVERE, no `Not scheduled yet`, no `NoClassDefFoundError`, no CME |

## Artifacts

- `P70-owner-tick.md` — owner contract + test/verification summary
- `subagent-p70\` — raw stdout/stderr, exit codes, surefire XML, diffstat, hashes
- `P65-dependency-live.md` — dependency and startup gate
- `P65-smoke-*.log/txt` — raw startup/shutdown/TPS evidence
- `P65-smoke-result.txt` — machine-readable pass flags
- `evidence-sha256.txt` — hash of every changed/new source file
- `execution-ledger.csv` — every command and exit code, including failed attempts

## Status

**PARTIAL** — tracker plan only; `WorldServer.doTick()` / `tickEntities()` were not moved.
Two world owner domains exist (`world-owner-0`, `world-owner-1`) but the idle smoke shows zero
production work consumed; `MultiWorldProductionConsumptionTest` is the synthetic proof.
`windypit.yml` schema ownership is still split between `WindyPitConfig` and `WindSpigotConfig`.
