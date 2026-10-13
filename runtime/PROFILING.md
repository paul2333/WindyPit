# WindyPit profiling

Use JDK 25 `jcmd` and `jfr` against the running server process. The profiler keeps recordings and reports under the selected output path and does not require a server restart.

On Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
$env:WINDYPIT_JAVA_HOME = $env:JAVA_HOME
python runtime\tools\jfr_profile.py list
python runtime\tools\jfr_profile.py start <pid> --duration 5m --threshold-ms 50 --output runtime\profiles\pit-50players.jfr
python runtime\tools\jfr_profile.py report runtime\profiles\pit-50players.jfr --threshold-ms 50 --sample-period-ms 10
```

On Linux or macOS, set `JAVA_HOME` to the server's JDK and replace `python` and the paths with their POSIX forms. The JDK running the server must allow local diagnostic commands; run the profiler as the same OS user as the server.

Capture separate, repeatable windows for idle, 20/50/100-player PvP, and feature stress with bots, holograms, market, audit, and replay enabled. Keep each recording duration and server configuration the same when comparing runs. Record the WindyPit and plugin revisions, player count, enabled workload, CPU model, memory, and GC selection next to each result. Do not compare a smoke-startup recording with a sustained PvP run.

The report separates expected idle worker-queue and server tick-pacing parks from contention and long operations. Both sections retain measured events at or above 50ms for monitor waits, parks, socket and file I/O, garbage collection, GC pauses, and safepoints; inspect the thread and stack before treating a wait as a gameplay bottleneck. JFR's own recorder thread and stack events are omitted.

CPU methods appear as sampled stack hotspots. The configured 10ms sample period provides a relative hotspot estimate; it does not establish that one method invocation took 50ms. Use repeatable CPU hotspots under the target workload to choose code for optimization, then recapture the same scenario to verify the change.

The checked-in `runtime/tests/fixtures/JfrWorkload.java` is a short synthetic capture fixture for validating the JFR start/report pipeline. Its output is not a server benchmark and must not be used to claim WindSpigot or Folia performance.

## Evidence boundary

A live profile requires the target server to be running and accessible to `jcmd` as the same operating-system user. If that setup is unavailable, record the capture as **not run**; do not synthesize a server profile from the test workload. Existing `windspigot-*` recordings are WindSpigot-specific and do not establish Folia behavior or player-load performance. The runtime smoke report's protocol probe is packet-level evidence; vanilla 1.8.8 client rendering, inventory UI, and PvP behavior remain separate manual checks.
