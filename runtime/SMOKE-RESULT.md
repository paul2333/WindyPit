# Folia protocol smoke result

- Status: **PASS**
- Run root: `C:\Users\ylxyl\OneDrive\Desktop\WindyPit\runtime\test-data\smoke-20261013T132707355522Z`
- Startup timeout: 180 seconds
- Shutdown result: stop command completed
- Command: `"C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe" -Xms512M -Xmx1G -XX:+UseG1GC -jar C:\Users\ylxyl\OneDrive\Desktop\WindyPit\runtime\server\folia-26.1.2-8.jar --nogui`
- Locked compatibility tuple: Folia 26.1.2 build 8; ViaVersion 5.12.1, ViaBackwards 5.12.1, ViaRewind 4.2.0, ThePitFoliaMigration 0.1.0-experimental.
- Protocol 47 probe: **PASS**; status ping, offline login, Join Game, movement packet, player inventory Window Items, and Click Window result recorded.
- Protocol 47 has no positive acknowledgement for an accepted no-op inventory click; the probe requires that the connection remain open and no rejected Confirm Transaction arrive during the settle window.
- This packet probe exercises protocol translation without a vanilla UI; manually verify a vanilla 1.8.8 client, inventory display, and PvP attack behavior before deployment.

## Observed failures or warnings

- No startup, plugin, shutdown, or protocol failures detected.
- Warning: WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
- Warning: WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.joml.MemUtil$MemUtilUnsafe (file:/C:/Users/ylxyl/OneDrive/Desktop/WindyPit/runtime/test-data/smoke-20261013T132707355522Z/libraries/org/joml/joml/1.10.8/joml-1.10.8.jar)
- Warning: WARNING: Please consider reporting this to the maintainers of class org.joml.MemUtil$MemUtilUnsafe
- Warning: WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
- Warning: [33;1m[21:27:32 WARN]: **** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!
- Warning: [33;1m[21:27:32 WARN]: The server will make no attempt to authenticate usernames. Beware.
- Warning: [33;1m[21:27:32 WARN]: While this makes the game possible to play without internet access, it also opens up the ability for hackers to connect with any username they choose.
- Warning: [33;1m[21:27:32 WARN]: To change this, set "online-mode" to "true" in the server.properties file.
- Warning: [33;1m[21:27:36 WARN]: [ViaBackwards] (1.17->1.16.4) Increased world height is NOT SUPPORTED for 1.16 players and below. They will see a void below y 0 and above 256. You can enable the `bedrock-at-y-0` config option to replace the air with a bedrock layer.

Full log: `C:\Users\ylxyl\OneDrive\Desktop\WindyPit\runtime\test-data\smoke-20261013T132707355522Z\server.log`
Protocol evidence: `C:\Users\ylxyl\OneDrive\Desktop\WindyPit\runtime\test-data\smoke-20261013T132707355522Z\protocol-probe.json`
