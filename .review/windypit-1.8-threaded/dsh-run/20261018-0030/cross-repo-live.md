# Cross-repo live run — ThePitUltra on WindSpigot 1.8.8 (run 20261018-0030)

Purpose: verify the committed WindyPit server jar and the committed ThePitUltra plugin jar load
together, that the plugin discovers the real kernel after server startup, and that both shut down
cleanly. Disposable server copy: `.review/.../server-smoke-plugin` (not a deliverable).

## Artifacts used

- WindSpigot.jar SHA-256 `50C74119A582339D5A9C38FC207FB74FEBB11D6F34EC0527F079595BA3FB4404`
- ThePitUltra-build-1.jar SHA-256 `BE7409B1DE12130A82F4086DD73A3D6BAFA4C94C60A5051088F05B0D1267B79A`
  (pre-adapter-fix build; the final rebuild changes this hash — see final report)
- Dependencies: LuckPerms-Bukkit-5.5.81, PlaceholderAPI-2.12.3, ProtocolLib 5.3.0 (from plugin libs).

## Result

- Server reached `Done (1.526s)` with ThePitUltra enabled.
- `WindyPit bootstrap initialized, mode: BUKKIT_FALLBACK, capability:
  KERNEL_CLASS_PRESENT_NOT_BOUND` — expected: plugin `onEnable` runs before DedicatedServer creates
  `WindyPitTickIntegration`.
- On the first scheduled-task execution after startup:
  `WindyPit kernel became available after startup; adapter upgraded to WINDYPIT_KERNEL`.
- Thread dump contains `WindyPit-Region-world-owner-0` and `-1`.
- `/windypit-tps` (RCON): REGION mode, TPS 20.02, MSPT 0.88, 2 world owner domains, per-world
  tick percentiles; no player had joined so `applied=0`.
- Plugin shutdown: `WindyPit scheduler shutdown summary: mode=WINDYPIT_KERNEL cap=KERNEL_BOUND
  ... kernel[qd=0,viol=0,seams=205 ...]`, then `WindyPit bootstrap shutdown completed`.
- Server stop: `Draining WindyPit scheduler...` → `drained successfully`; worlds saved.

## External / gameplay blockers observed

1. ThePitUltra's dependency manager tried to download Adventure artifacts from
   `https://repo.panda-lang.org/...` and received 404 (`adventure-text-serializer-gson-4.13.1`,
   `adventure-key-4.13.1`, ...). The plugin still enabled, but this is a real external dependency
   repository failure for a fully clean deployment.
2. The plugin logs `NoClassDefFoundError: com/sk89q/worldedit/world/World` while initializing
   epic events because no compatible WorldEdit jar was installed. This is a pre-existing plugin
   dependency gap, not caused by the WindyPit changes.
3. The protocol-47 probe against the ThePit-enabled server failed with
   `connection closed while reading VarInt`; the same probe succeeds against the bare WindyPit
   server (`P10-protocol47-probe.json`). The plugin-side login/protocol path is therefore
   **PARTIAL / BLOCKED_EXTERNAL** until a real 1.8.8 client session is run.
4. No combat/market/trade/NPC gameplay was exercised with a real client; only startup, task
   scheduling, kernel discovery, command diagnostics and shutdown.

## Evidence files

- `P40-plugin-smoke3-stdout.log` / `P40-plugin-smoke3-stderr.log`
- `P40-plugin-smoke3-result.txt` (`STARTED=True`, `KERNEL_UPGRADED=True`,
  `BOOTSTRAP_FALLBACK=True`, `STOP_EXITED=True`)
- `P40-plugin-smoke3-thread-dump.txt`, `P40-plugin-smoke3-owner-threads.txt`
- `P40-plugin-smoke3-tps.txt`, `P40-plugin-smoke3-stop.txt`
- `P40-plugin-boundary-scan.log` (plugin jar CLEAN, 10 legacy WARN entries)
