# FINAL REPORT — 20261018-0945 (WindyPit / ThePitUltimate)

Final status: **PARTIAL**
No `RELEASE_READY` claim. No reset / clean / force-checkout / amend / push was performed.
`20261018-0030` was not overwritten.

## Baselines

| Repo | Branch | HEAD (start = end) |
|---|---|---|
| WindyPit | `windypit-1.8-threaded` | `395fe4765d2757b3b567fa2eeabe358cbf12e17c` |
| ThePitUltimate | `thepitultimate-1.8-threaded` | `729e23948cd4238d5f8ce2166236cc10f04fe59d` |

## Executed work

### Prompt 50 — combat plan production wiring (ThePitUltimate)

- `CombatListener.applyAttackerBoosts` now captures one immutable `CombatSnapshot` on the arena
  owner, calls `CombatRuntime.resolve` (owner assertion + generation + combat sequence) and
  `CombatDamageCalculator.calculateEventDamage`, then applies the owner intent.
- Feature switch `windypit.features.combat-plan` (default true; `combat-calculator` kept as an
  alias). Rollback path is the untouched legacy arithmetic and is proven bit-identical.
- Tests: `CombatPlanProductionWiringTest`, `CombatParityFixtureTest`, `CombatGenerationRaceTest`,
  `CombatRollbackTest`, `CombatNoIoHotPathTest` — all PASS.
- Honest limit: death/reward path not migrated; runtime is owner-synchronous (the damage event is
  synchronous and must stay on the arena owner).

### Prompt 55 — durable commerce ledger (ThePitUltimate)

- Ledger schema extended (type, request hash, profile versions, reservation, recovery outcome,
  version/attempt); `BEGIN IMMEDIATE` cross-JVM claim; commit re-checks reservation/version.
- `CommerceCoordinator`: bounded single writer, commit-before-live-apply, replay/conflict,
  drain with abandoned-command recording.
- Durable consumers: market buy (pre-existing), points transfer, points debit/credit, membership
  tier and standalone membership (points + profile snapshot in one transaction).
- Crash matrix: reserve / commit / partial owner apply / duplicate-different-request /
  second-JVM claim / shutdown drain, all with real child JVMs and `Runtime.halt(42)`.
- Honest limit: trade, mail, kill reward and booster are still not ledgered; `ThePit.onDisable`
  does not yet drain a production coordinator.

### Prompt 60 — bot / NPC / visual

- Audit completed (`audit-bot-visual/bot-npc-visual-map.md`).
- Fixed three real lifecycle bugs: nametag `Not scheduled yet`, hologram cancel race,
  `AnimationRunnable` class-init NPE.
- Honest limit: `com.windypit.pit.{bot,npc,visual}` is still missing; five-channel dedup is 1/5;
  bot/NPC owner routing is unchanged. No completion claim.

### Prompt 65 — dependency closure and live gate

- Removed the dead panda repository; bundled Adventure/Hutool/annotations are detected and never
  downloaded; resolution failures are logged as `[Dependencies] SEVERE`.
- WorldEdit is an explicit event capability with a startup warning and event skip list.
- ProtocolLib handlers are unregistered on disable; `YamlCommenter` no longer aborts config saves
  for absent optional keys.
- Clean-server smoke (new WindSpigot + ThePit jars, `combat-plan=true`):
  `DONE=True`, plugin enabled, TPS `20.02`, clean RCON stop, and **zero**
  `Not scheduled yet` / `NoClassDefFoundError` / `ConcurrentModificationException` /
  `[Dependencies] SEVERE` / `repo.panda-lang.org`.
- Honest limit: no attended/scripted real protocol-47 client session; that sub-gate is PARTIAL.

### Prompt 70 — server owner/tick (WindyPit, delegated writer + orchestrator verification)

- `owner-contract.md`, `WorldOwnerRegistry`, immutable cross-owner messages, world generation
  assertions, 23 new tests.
- `mvn -o -B clean install`: **203 tests, 0 failures, 0 errors, 0 skipped**.
- `WorldServer.doTick()` / `tickEntities()` were **not** moved. Two world owner domains are
  registered; idle smoke counters are zero, so production consumption is only proven by
  `MultiWorldProductionConsumptionTest`, not by a live load.

### Prompt 80 — performance / JFR

- Only idle 0-player data exists (TPS 20.02, MSPT 0.68). No 20/50/100 runs and no loaded JFR;
  the release gate is **NOT PASSED**.

## Verification totals

| Check | Result |
|---|---|
| WindyPit `mvn -o -B clean install` | exit 0, 203 tests, 0/0/0 |
| ThePitUltimate `:base:test --rerun-tasks` | exit 0, **1596 tests, 0 failures, 0 errors, 0 skipped** |
| ThePitUltimate `:base:check` | exit 0 |
| ThePitUltimate `:base:build` | exit 0, `ThePitUltra-build-1.jar` |
| `git diff --check` (both) | exit 0 |
| Clean server startup/stop | exit 0, no ERROR/SEVERE |

Artifact SHA-256:

```
WindSpigot.jar          0801BCB43F8A5663EB4102048D874C1FB094053DE1B223577E911D67CD25390A
ThePitUltra-build-1.jar 02D6CE2D4FEE699E0B44016481F9D08562BBC506D9EBE988836527ABF26DD92C
```

## Why PARTIAL

1. Trade / mail / kill reward / booster are not durable-ledgered.
2. Bot/NPC/visual integration layer is missing.
3. No real protocol-47 client gameplay session.
4. No 20/50/100 load or loaded JFR; 50 ms+ stacks unclassified.
5. World/entity tick parallelism not proven; tracker planning only.

## Evidence

- WindyPit: `.review\windypit-1.8-threaded\dsh-run\20261018-0945\`
  (`run-index.md`, `execution-ledger.csv`, `P70-owner-tick.md`, `P65-dependency-live.md`,
  `P65-smoke-*`, `subagent-p70\`, `evidence-sha256.txt`)
- ThePitUltimate: `.review\thepitultimate-1.8-threaded\dsh-run\20261018-0945\`
  (`run-index.md`, `execution-ledger.csv`, `P50-combat-owner.md`, `P55-commerce-ledger.md`,
  `P60-bot-npc-visual.md`, `P80-perf-jfr.md`, `ACCEPTANCE-VERIFICATION.md`,
  `audit-commerce\`, `audit-bot-visual\`, `evidence-sha256.txt`)

---

## Addendum (round 10)

- ProtocolLib 5.3.0 login closure fixed by JVM flags (`--add-opens java.base/java.lang|java.net|java.nio=ALL-UNNAMED`); ThePit also needs ~1 s after `Done (` before accepting logins.
- Protocol-47 probe PASS; real load clients 20/20, 50/50, 100/100 joined and moved.
- 0/20/50/100: TPS 20.02/20.01/20.02/20.02; world tick p99 0.49/4.28/5.36/18.63 ms; JFR has no unexplained server-thread 50 ms+ stack (only tick pacing and shutdown drain).
- Gate remains PARTIAL: ~60 s movement load, not 10 min; no PvP/projectile/death/GUI client session.
- Real Minecraft 1.8.9 client logged in to the protocol-47 server (`PitTester`, entity 439) and opened its inventory (`Taking Inventory` achievement) via a CUA key event. Combat/projectile/death remain undriven.
- Final verification: plugin 1605/0/0/0, server 203/0/0/0, both `git diff --check` exit 0.

