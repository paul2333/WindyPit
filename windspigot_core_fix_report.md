# WindSpigot core safety fix report

## Scope
Updated only the world ticker and entity-tracker compatibility classes:

- `WindSpigot-Server/src/main/java/com/windpvp/windspigot/world/WorldTicker.java`
- `WindSpigot-Server/src/main/java/com/windpvp/windspigot/async/entitytracker/AsyncEntityTracker.java`

## Changes

- Removed the `WorldTicker`-owned `ResettableLatch` and its public accessor. Tracker work no longer shares a reusable latch between ticks.
- Made `WorldTicker` invoke the world tracker on the server thread for every tick and stop tracker timings in a `finally` block, so tracker updates cannot be silently skipped.
- Removed the unsafe asynchronous `EntityTrackerEntry.update(...)` executor path. `AsyncEntityTracker` remains as the tracker type used by existing world construction but inherits `EntityTracker.updatePlayers()`, preserving synchronous Bukkit/NMS access and plugin-event execution.
- Removed the executor, queue, and latch completion dependencies from the compatibility tracker.

## Verification

A focused source sanity check passed for:

- no `ResettableLatch`/`getLatch` usage in `WorldTicker`;
- no async-tracker skip branch;
- unconditional `worldserver.getTracker().updatePlayers()` in `WorldTicker`;
- no async override or executor in `AsyncEntityTracker`.

Verified on 2026-10-13 with Apache Maven 3.9.16 and Microsoft JDK 25.0.3: `mvn -B -ntp clean install` completed successfully, including 22 server tests with no failures or errors.

