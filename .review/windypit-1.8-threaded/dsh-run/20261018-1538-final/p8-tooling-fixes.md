# P8 tooling fixes — RED/GREEN evidence, root causes and residual risk

Round: `20261018-1538-final` (phase 8, tooling defect closure)
Repository: `C:\Users\ylxyl\OneDrive\Desktop\WindyPit`
Branch: `windypit-1.8-threaded`
Scope: `runtime/tools/` and `runtime/tests/` only. No server Java source was modified,
no `git commit/reset/clean/push` was performed, and `ThePitUltimate-release` was only read
(to confirm `DurableTransactionLedger` / `PointLedger` semantics). No long server load was run;
the `run_scenario.ps1` changes were verified by contract tests, PowerShell AST parsing and
isolated helper execution.

## 1. Defects and fixes at a glance

| # | Defect | Root cause | Fix |
|---|---|---|---|
| 1 | Sampler failure skipped JFR dump/stop | `run_scenario.ps1` validated the sampler summary at :504-515 and only dumped/stopped JFR afterwards (:519-521). Any throw jumped to `catch`/`finally`, which killed the server. `JFR.stop` exit code was never read, and `JFR.dump` failure was fatal only when the file was absent — `JFR.start` with `filename=` creates the file immediately, so a failed dump was normally swallowed. | New `Stop-JfrRecording` helper always runs `JFR.dump` then `JFR.stop`, captures both exit codes, verifies the dump file exists with `Length > 0`, and aggregates step-specific errors. It is called before the sampler wait/validation. `finally` retries `JFR.stop` before killing the server and waits for process exit. |
| 2 | Scenario reused stale outputs | `run_scenario.ps1` only `New-Item`-ed `metrics/ jfr/ clients/` and left `gate-summary.json` / `ledger.json`; only the `server/` directory was archived. A failed run could therefore leave old evidence that looked current. | New `Clear-ScenarioOutputs` deletes `metrics/`, `jfr/`, `clients/`, `logs/`, `gate-summary.json`, `gate-report.md`, `ledger.json` under the scenario directory, path-checks every target, asserts the paths are gone, then recreates the directories. New `Assert-FreshOutput` rejects any key artifact whose `LastWriteTimeUtc` predates the run start (2 s tolerance). |
| 3 | Missing inputs were equivalent to a pass | `gate_eval.py` returned 0 for `PARTIAL`; `run_scenario.ps1` only treated `exit >= 2` as failure and then set `status = completed`. Missing `tick_p99`, an accepted-work counter or the ledger only downgraded the report. | Exit-code contract is now explicit: `0 PASS`, `1 FAIL`, `2 PARTIAL/MISSING_INPUT`, `3 input/output error`. `run_scenario.ps1` treats every non-zero gate exit as scenario failure and includes the overall status and blocking reasons. `gate-report.md` now has a `## Missing inputs (MISSING_INPUT)` section plus a `Missing input` column. |
| 4 | Whole-table `RESERVED` assertion false positives | `verify_ledger.py --assert-no-reserved` counted every `transaction_ledger` row with `state='RESERVED'` (:172-175, :201, :387-393), while the kill-reward journal is the documented steady-state RESERVED producer. The module docstring (:7) and the CLI help (:358) described different behaviour. | Added repeatable `--assert-no-reserved-type` (default `kill-reward`; `all` restores the whole-table check). `build_report` now returns `reserved_by_type`, `asserted_types`, `asserted_reserved` and `assertions.no_reserved_for_asserted_types`; only the asserted types can exit 2. Docstring and help now agree. |
| 5 | Empty JFR recording passed the gate | `jfr_classify.py` with `events=[]` produced `duration_event_count=0` / `unexplained_repeated=0`, which satisfied every downstream check. There was no minimum-event requirement. | Added `MIN_DURATION_EVENTS = 1` and `--min-duration-events` (default 1). `gate.json` now carries `min_duration_events` and `empty_recording`; `empty_recording=true` makes the CLI exit 3 after writing the outputs, and `gate_eval.py` adds a `jfr_recording_nonempty` check that FAILs on `empty_recording=true` (PARTIAL when the flag is absent). |

## 2. RED phase (tests written first)

Command:

```powershell
python -m unittest runtime.tests.test_run_scenario_contract runtime.tests.test_gate_eval runtime.tests.test_verify_ledger runtime.tests.test_jfr_classify -v
```

Result (`p8-red-tooling-tests.log`): **Ran 55 tests — FAILED (failures=8, errors=17, skipped=1)**.
The skip was the PowerShell-AST test at that moment (`pwsh` is not on `PATH` on this host; the
test was later changed to fall back to Windows PowerShell). Representative failures:

- `test_run_scenario_contract`: `test_jfr_is_dumped_and_stopped_before_sampler_validation`,
  `test_scenario_outputs_are_cleared_before_start`, `test_freshness_assertion_helper_is_used`,
  `test_finally_stops_jfr_before_killing_server` — the old script had no helpers and validated
  the sampler before JFR.
- `test_gate_eval`: `test_partial_exits_2_and_reports_missing_inputs` (`0 != 2`),
  `test_missing_file_exits_3` (`2 != 3`), `test_invalid_threshold_exits_3` (`2 != 3`),
  `test_empty_recording_failure` (`PASS != FAIL`), `test_missing_empty_recording_flag_is_partial`.
- `test_verify_ledger`: `test_market_reserved_passes_default_assertion` (`2 != 0`),
  `test_assert_no_reserved_type_market_buy_exits_2`,
  `test_assert_no_reserved_type_all_exits_2`,
  `test_market_reserved_is_not_a_kill_reward_reservation` — the old CLI had no type selector.
- `test_jfr_classify`: `test_empty_recording_cli_exits_3_and_writes_gate` (`0 != 3`),
  `test_empty_recording_is_flagged`, `test_sample_only_recording_is_flagged`,
  `test_minimum_event_threshold_is_configurable`.

## 3. GREEN phase and self-checks

### 3.1 Targeted suite

```powershell
python -m unittest runtime.tests.test_run_scenario_contract runtime.tests.test_gate_eval runtime.tests.test_verify_ledger runtime.tests.test_jfr_classify -v
```

Result (`p8-green-tooling-tests.log`): **Ran 55 tests — OK**, exit 0.

### 3.2 Full runtime suite

```powershell
python -m unittest discover -s runtime/tests -p 'test_*.py'
```

Result (`p8-full-tests.log`): **Ran 111 tests — OK**, exit 0.

### 3.3 PowerShell AST parse

```powershell
$tokens = $null; $errors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path 'runtime\tools\run_scenario.ps1').Path, [ref]$tokens, [ref]$errors)
```

Result (`p8-ast-parse.txt`): **PARSER_ERRORS=0**, `TOKEN_COUNT=4101`.

### 3.4 Modified-tool self-tests

```powershell
python runtime\tools\gate_eval.py --self-test
python runtime\tools\verify_ledger.py --self-test
python runtime\tools\jfr_classify.py --self-test
```

Result (`p8-modified-tool-self-tests.log`): all three print `self-test OK`, exit 0.

### 3.5 Isolated `run_scenario.ps1` helper execution

The two new helpers were extracted from the parsed AST and executed against a temporary
scenario directory (no server start). Result (`p8-run-scenario-helper-check.txt`):

- stale `metrics/`, `jfr/`, `clients/`, `logs/` content and the three gate files removed:
  `STALE_REMAINING=0`; all four directories recreated.
- a file written after run start passes `Assert-FreshOutput` (`FRESH_PASS`).
- a file back-dated by two hours is rejected:
  `STALE_REJECTED=... predates run start ...` (`STALE_THREW=True`).

## 4. Per-item root cause and fix detail

### 4.1 JFR finalization before sampler validation (`run_scenario.ps1`)

Old order: sleep measurement window → sampler `WaitForExit` → metrics-summary existence and
`errors > 0` checks (throw) → `JFR.dump` → `JFR.stop` (exit ignored) → dump-failure check only
when the file did not exist.

New order (`run_scenario.ps1:619-634`):

1. `serverProc.HasExited` guard (specific error: `server exited before JFR dump/stop`).
2. `Stop-JfrRecording` (`:269-319`):
   - always runs `JFR.dump`, captures `$dumpExit`;
   - always runs `JFR.stop`, captures `$stopExit`;
   - adds a problem for each non-zero exit code;
   - checks `Test-Path` and `Length -gt 0` for the dump file;
   - throws `JFR finalization failed: <step-specific problems>`.
3. `Assert-FreshOutput` on the recording.
4. Only then waits for the sampler and validates `metrics-summary.json`.

Teardown (`:746-791`) now stops JFR before killing the server:

- if `$script:jfrActive` is still true and the server is alive, it runs `JFR.stop` again and logs
  `jfr-stop-finally.log`;
- then kills the server and waits up to 30 s for exit.

This covers sampler failure, client failure, gate failure and any throw between JFR start and
normal stop: the recording is stopped and the server process is terminated.

### 4.2 No stale scenario outputs (`run_scenario.ps1`)

- `Clear-ScenarioOutputs` (`:211-251`) runs at the top of the `try` block (`:472`), before the
  server directory is archived/recreated and before any client/JFR start.
- It removes `metrics/`, `jfr/`, `clients/`, `logs/`, `gate-summary.json`, `gate-report.md` and
  `ledger.json`; every target must resolve under the scenario directory, and the function throws
  `stale scenario output was not cleared` if anything survives.
- `Assert-FreshOutput` (`:253-267`) compares `LastWriteTimeUtc` against `$runStartedAtUtc`
  (captured before cleanup, 2 s tolerance) and is applied to:
  `recording.jfr` (:624), `metrics-summary.json` (:632), load-client result (:648),
  pvp result (:656), `jfr/gate.json` (:683), `ledger.json` (:703) and `gate-summary.json` (:724).

### 4.3 Gate exit codes and MISSING_INPUT (`gate_eval.py`, `run_scenario.ps1`)

`gate_eval.py` now defines `EXIT_PASS=0`, `EXIT_FAIL=1`, `EXIT_PARTIAL=2`, `EXIT_ERROR=3`
and returns them from `main` (`:494-497`). Input/output errors (missing file, malformed JSON,
non-positive thresholds, write failure) return 3; `PARTIAL` returns 2.

Every check carries `missing_input`; the summary carries `missing_inputs`
(`gate_eval.py:301-313`), and `gate-report.md` renders:

```text
## Missing inputs (MISSING_INPUT)
- unexplained_repeated
- kill_reward_reserved
```

plus a `Missing input` column in the check table (`:347-350`, `:361-368`).

`run_scenario.ps1:716-737`:

- exit 3 → `gate_eval failed with exit code 3 (input/output error)` (no summary expected);
- exit 1 → `gate_eval reported FAIL (exit 1): overall=...; <reasons>`;
- exit 2 → `gate_eval reported PARTIAL with missing inputs (exit 2): overall=...; <reasons>`;
- any other non-zero → generic failure with the code;
- only exit 0 reaches `$status = 'completed'`.

### 4.4 Ledger assertion scoped to the asserted type (`verify_ledger.py`)

- `DEFAULT_ASSERT_TYPES = ("kill-reward",)` (`:30`).
- `--assert-no-reserved-type TYPE` is repeatable (`:410-414`); `all` or `*` restores the old
  whole-table check (`_asserted_types`, `:141-151`).
- `build_report` reports `reserved_by_type`, `asserted_types`, `asserted_reserved` and
  `assertions.no_reserved_for_asserted_types` (`:230-240`). `assertions.no_reserved` remains as
  an informational whole-table value.
- CLI exits 2 only when `asserted_reserved > 0`; the message names the asserted types and still
  shows the whole-table count (`:444-449`).
- Module docstring (`:1-15`) and CLI help (`:402-414`) now state the same contract: default
  `kill-reward`, `--assert-no-reserved-type all` for whole-table.

### 4.5 Empty JFR recording is a failure (`jfr_classify.py`, `gate_eval.py`)

- `MIN_DURATION_EVENTS = 1` (`:46`); `analyze(..., min_duration_events=...)` rejects values < 1.
- `gate.json` gains `min_duration_events` and `empty_recording = duration_event_count <
  min_duration_events` (`:375-376`); the CLI exposes `--min-duration-events` (`:597-603`).
- The CLI still writes `classification.csv`, `hotspots.csv` and `gate.json`, then exits 3 with
  `empty recording: duration_event_count=0 < min_duration_events=1` (`:638-644`).
- `gate_eval.py:215-240` adds `jfr_recording_nonempty`: `empty_recording=true` → FAIL;
  missing flag → PARTIAL/MISSING_INPUT. This prevents an old or hand-written `gate.json` from
  bypassing the check.

## 5. Modified files

| File | Change |
|---|---|
| `runtime/tools/run_scenario.ps1` | JFR dump/stop helper + ordering; stale-output reset; freshness assertions; non-zero gate handling; teardown JFR stop. |
| `runtime/tools/gate_eval.py` | 0/1/2/3 exit contract; `missing_input` flags and `missing_inputs`; `MISSING_INPUT` report section; empty-recording check. |
| `runtime/tools/verify_ledger.py` | Type-scoped `--assert-no-reserved` + repeatable `--assert-no-reserved-type` (default `kill-reward`, `all` supported); report fields; docstring/help alignment. |
| `runtime/tools/jfr_classify.py` | Minimum duration-event threshold, `empty_recording`/`min_duration_events` in `gate.json`, CLI exit 3. |
| `runtime/tests/test_run_scenario_contract.py` | Added JFR ordering, teardown, stale-output, freshness, gate-exit and AST-parse tests. |
| `runtime/tests/test_gate_eval.py` | Added PARTIAL=2, ERROR=3, MISSING_INPUT report, empty-recording and missing-flag tests. |
| `runtime/tests/test_verify_ledger.py` | Added market-buy RESERVED (default pass), type selector and `all` tests; report assertions. |
| `runtime/tests/test_jfr_classify.py` | Added empty/sample-only recording, configurable minimum, CLI exit-3 tests. |

No changes were made to `WindSpigot-Server/src/**` or any other tracked server source.

## 6. Exact verification commands

```powershell
cd C:\Users\ylxyl\OneDrive\Desktop\WindyPit
python -m unittest runtime.tests.test_run_scenario_contract runtime.tests.test_gate_eval runtime.tests.test_verify_ledger runtime.tests.test_jfr_classify -v
python -m unittest discover -s runtime/tests -p 'test_*.py'
$tokens = $null; $errors = $null
[System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path 'runtime\tools\run_scenario.ps1').Path, [ref]$tokens, [ref]$errors)
"PARSER_ERRORS=$($errors.Count)"
```

Observed: targeted `Ran 55 tests ... OK`; full `Ran 111 tests ... OK`; `PARSER_ERRORS=0`.

Evidence files in this directory:

- `p8-red-tooling-tests.log` — RED run.
- `p8-green-tooling-tests.log` — targeted GREEN run.
- `p8-full-tests.log` — full discovery run.
- `p8-ast-parse.txt` — AST parse result.
- `p8-modified-tool-self-tests.log` — `--self-test` results for the three modified tools.
- `p8-run-scenario-helper-check.txt` — isolated helper execution transcript.
- `p8-python-tools-check.log` — intermediate Python-tool run (kept for traceability).
- `p8-tooling-fixes.md` — this document.

## 7. Residual risks / notes

1. **No live scenario was run.** The user prohibited long server load, so `run_scenario.ps1` is
   verified by AST parsing, contract tests and isolated helper execution, not by a real
   warm-up/measure/JFR cycle. The next real scenario run is the first end-to-end exercise of the
   reordered JFR path.
2. **Bare scenarios now fail the gate.** Because `gate_eval` returns 2 for a missing ledger and
   `run_scenario` treats every non-zero exit as failure, a scenario run without `-UseThePit`
   (no `ledger.json`) ends as `PARTIAL`/exit 1. This is the requested "missing evidence is not a
   pass" policy; use `-SkipGate` for tooling-only smoke runs or supply ThePit evidence.
3. **Ledger assertion semantics.** The default assertion is `kill-reward`, as requested. The
   plugin source describes `kill-reward` RESERVED as the steady state between kill and profile
   save (`DurableTransactionLedger.TYPE_KILL_REWARD`, `PointLedger.recoverIncompleteExcluding`),
   so the assertion is meaningful only after a clean shutdown/save (the position `run_scenario`
   uses) and a nonzero count there should be treated as a stuck reward. If the intended policy is
   instead to exempt `kill-reward`, change `DEFAULT_ASSERT_TYPES` to the complementary set and
   use `--assert-no-reserved-type all` for the strict form.
4. **Old `gate.json` files become PARTIAL.** `gate_eval` requires the new `empty_recording` flag;
   a gate.json produced before this change yields `MISSING_INPUT`/exit 2. Re-run
   `jfr_classify.py` to regenerate it.
5. **Empty recording exits before gate-report.** `jfr_classify.py` writes `gate.json` with
   `empty_recording=true` and exits 3; `run_scenario` fails the scenario at that point, so no
   `gate-report.md` is produced for that failure. The JFR evidence is still on disk.
6. **Path safety is lexical.** `Clear-ScenarioOutputs` rejects targets outside the scenario
   directory by absolute-path prefix, but does not resolve symlinks/junctions. The scenario
   directory is created by the script under `perf/<scenario>` and the scenario name is restricted
   to `[A-Za-z0-9._-]+`.
7. **Freshness tolerance.** `Assert-FreshOutput` allows a 2 s clock tolerance; filesystem
   timestamp granularity is expected to be finer than that on NTFS.
