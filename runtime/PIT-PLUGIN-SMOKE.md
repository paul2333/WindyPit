# ThePit Folia Plugin Smoke

- Date: 2026-10-12
- Runtime: Folia 26.1.2 build 8, Java 25.0.3
- Compatibility plugins: ViaVersion 5.12.1, ViaBackwards 5.12.1, ViaRewind 4.2.0
- Artifact: `ThePitFoliaMigration-build-1.jar`
- Result: **PASS**

The pinned Folia runtime initialized, loaded, enabled, and disabled
`ThePitFoliaMigration` without a plugin-load error. The plugin log reported the
experimental ownership combat adapters disabled by default, then initialized
the migration foundation. Shutdown completed through Folia's scheduler and
region shutdown sequence.

This verifies plugin loadability, Java 25 startup, Via compatibility, and clean
disable. It does not claim ThePit gameplay parity: the feature matrix still
marks economy, market, trade, mailbox, NPC, bot, replay, scoreboard, nametag,
permissions, and complete combat state bindings as pending or partial.
