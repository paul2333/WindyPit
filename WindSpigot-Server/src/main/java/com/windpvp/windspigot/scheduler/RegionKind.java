
package com.windpvp.windspigot.scheduler;

/**
 * Logical kind of an owned region. Kinds only carry scheduling policy hints; they never map to
 * Folia region ids and never leak into chunk addressing.
 */
public enum RegionKind {
    /** The shared ThePit gameplay arena; default owner is the primary server thread. */
    ARENA,
    /** NPC driven regions. */
    NPC,
    /** Bot regions with an independent AI budget. */
    BOT,
    /** Decoration / hologram regions. */
    DECOR,
    /** Worlds without players that still need scheduled maintenance. */
    WORLD,
    /** Anything else registered by later waves. */
    CUSTOM
}

