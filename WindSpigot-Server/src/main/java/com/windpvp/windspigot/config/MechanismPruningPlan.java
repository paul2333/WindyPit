
package com.windpvp.windspigot.config;

/**
 * Mechanism pruning plan (W5-PRUNING). Derived from {@link WindyPitConfig} when
 * features.mechanism-pruning is enabled. Each toggle corresponds to a mechanism that ThePit
 * does not need but vanilla Spigot ticks by default. PvP-critical features (projectiles,
 * potions, map protection, teleport, combat events) are NEVER pruned.
 *
 * The NMS layer reads this plan to apply per-mechanism disable calls.
 */
public final class MechanismPruningPlan {

    private final boolean disableNaturalSpawning;
    private final boolean disableHostileMobSpawning;
    private final boolean disableWeatherAndDaylight;
    private final boolean disableFarmingAndFurnaces;
    private final boolean disableVillagers;
    private final boolean disableRedstoneAndHoppers;
    private final boolean disableRandomTickNoPlayerRegions;
    private final boolean disableNonArenaEntityMaintenance;

    private MechanismPruningPlan(Builder builder) {
        this.disableNaturalSpawning = builder.disableNaturalSpawning;
        this.disableHostileMobSpawning = builder.disableHostileMobSpawning;
        this.disableWeatherAndDaylight = builder.disableWeatherAndDaylight;
        this.disableFarmingAndFurnaces = builder.disableFarmingAndFurnaces;
        this.disableVillagers = builder.disableVillagers;
        this.disableRedstoneAndHoppers = builder.disableRedstoneAndHoppers;
        this.disableRandomTickNoPlayerRegions = builder.disableRandomTickNoPlayerRegions;
        this.disableNonArenaEntityMaintenance = builder.disableNonArenaEntityMaintenance;
    }

    public boolean disableNaturalSpawning() { return disableNaturalSpawning; }
    public boolean disableHostileMobSpawning() { return disableHostileMobSpawning; }
    public boolean disableWeatherAndDaylight() { return disableWeatherAndDaylight; }
    public boolean disableFarmingAndFurnaces() { return disableFarmingAndFurnaces; }
    public boolean disableVillagers() { return disableVillagers; }
    public boolean disableRedstoneAndHoppers() { return disableRedstoneAndHoppers; }
    public boolean disableRandomTickNoPlayerRegions() { return disableRandomTickNoPlayerRegions; }
    public boolean disableNonArenaEntityMaintenance() { return disableNonArenaEntityMaintenance; }

    /** Derives the plan from the config using the global pruning defaults. */
    public static MechanismPruningPlan fromConfig(WindyPitConfig config) {
        return fromConfig(config, null);
    }

    /**
     * Derives the effective plan for one world: {@code features.mechanism-pruning} is the master
     * switch, each mechanism resolves world override -&gt; global value -&gt; default true, and the
     * non-arena entity-maintenance switch additionally respects the async-tracking rollback flag.
     */
    public static MechanismPruningPlan fromConfig(WindyPitConfig config, String worldName) {
        boolean enabled = config.features().mechanismPruning();
        boolean rollback = config.rollback().disableAsyncTracking();
        WindyPitConfig.PruningConfig pruning = config.pruning();
        Builder b = new Builder();
        b.disableNaturalSpawning(enabled && pruning.isDisabled(worldName, WindyPitConfig.PruningConfig.NATURAL_SPAWNING));
        b.disableHostileMobSpawning(enabled && pruning.isDisabled(worldName, WindyPitConfig.PruningConfig.HOSTILE_MOB_SPAWNING));
        b.disableWeatherAndDaylight(enabled && pruning.isDisabled(worldName, WindyPitConfig.PruningConfig.WEATHER_AND_DAYLIGHT));
        b.disableFarmingAndFurnaces(enabled && pruning.isDisabled(worldName, WindyPitConfig.PruningConfig.FARMING_AND_FURNACES));
        b.disableVillagers(enabled && pruning.isDisabled(worldName, WindyPitConfig.PruningConfig.VILLAGERS));
        b.disableRedstoneAndHoppers(enabled && pruning.isDisabled(worldName, WindyPitConfig.PruningConfig.REDSTONE_AND_HOPPERS));
        b.disableRandomTickNoPlayerRegions(enabled && pruning.isDisabled(worldName, WindyPitConfig.PruningConfig.RANDOM_TICK_NO_PLAYER_REGIONS));
        b.disableNonArenaEntityMaintenance(enabled && !rollback
                && pruning.isDisabled(worldName, WindyPitConfig.PruningConfig.NON_ARENA_ENTITY_MAINTENANCE));
        return b.build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean disableNaturalSpawning;
        private boolean disableHostileMobSpawning;
        private boolean disableWeatherAndDaylight;
        private boolean disableFarmingAndFurnaces;
        private boolean disableVillagers;
        private boolean disableRedstoneAndHoppers;
        private boolean disableRandomTickNoPlayerRegions;
        private boolean disableNonArenaEntityMaintenance;

        public Builder disableNaturalSpawning(boolean v) { this.disableNaturalSpawning = v; return this; }
        public Builder disableHostileMobSpawning(boolean v) { this.disableHostileMobSpawning = v; return this; }
        public Builder disableWeatherAndDaylight(boolean v) { this.disableWeatherAndDaylight = v; return this; }
        public Builder disableFarmingAndFurnaces(boolean v) { this.disableFarmingAndFurnaces = v; return this; }
        public Builder disableVillagers(boolean v) { this.disableVillagers = v; return this; }
        public Builder disableRedstoneAndHoppers(boolean v) { this.disableRedstoneAndHoppers = v; return this; }
        public Builder disableRandomTickNoPlayerRegions(boolean v) { this.disableRandomTickNoPlayerRegions = v; return this; }
        public Builder disableNonArenaEntityMaintenance(boolean v) { this.disableNonArenaEntityMaintenance = v; return this; }
        public MechanismPruningPlan build() { return new MechanismPruningPlan(this); }
    }
}

