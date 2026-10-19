# Prompt 65 — real 1.8.9 client session (partial)

Round: `20261018-0945`
Server: WindSpigot 1.8.8 protocol 47, `server-smoke-p65`, JVM flags from `P65-dependency-live.md`
Client: real Minecraft 1.8.9 Forge client launched from the installed PCL profile
(`C:\Users\ylxyl\OneDrive\Desktop\PCL\LatestLaunch.bat`, username `PitTester`), with
`--server 127.0.0.1 --port 8823`.

## Evidence

Server log (`P80-client-server-stdout.log`):

```
[12:45:30 INFO]: UUID of player PitTester is b0a0fcc0-a358-3877-b5c1-8fdc5d484f94
[12:45:30 INFO]: PitTester[/127.0.0.1:6086] logged in with entity id 439
                 at ([world]1.1394877433776855, 114.0, 9.438972473144531)
[12:47:45 INFO]: PitTester has just earned the achievement [Taking Inventory]
[12:48:32 INFO]: PitTester lost connection: Internal Exception: java.net.SocketException: Connection reset
```

Window title observed: `Minecraft* 1.8.9 - Multiplayer (3rd-party Server)` (pid 39128, hwnd
16515130). A CUA `press_key("e")` was delivered to the client and produced the vanilla
`Taking Inventory` achievement, proving the client processed a real inventory-open GUI action.

## What this establishes

- A real 1.8.9 client (not the protocol probe, not the load generator) completed protocol-47
  login and joined the WindSpigot server.
- The client opened its inventory against the live server (GUI transaction evidence).
- The session lasted ~3 minutes; disconnect at 12:48:32 was caused by the orchestrator
  terminating the client process after evidence capture.

## Not yet established

- Movement/combat/knockback/projectile/death/reward were not driven or verified in this session.
- The OpenGL client window returns a blank image through `PrintWindow`/window capture; desktop
  capture did not show the client either, so no visual screenshot of the in-game HUD is stored.
  Evidence is server-side log + window title + CUA key delivery.
- No client-side recording/script exists yet for a repeatable full gameplay run.

Gate status: real-client **login + inventory GUI = PASS**; full gameplay gate remains **PARTIAL**.
