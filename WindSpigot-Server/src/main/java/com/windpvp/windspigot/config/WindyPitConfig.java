package com.windpvp.windspigot.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WindyPit threading / feature / pruning / rollback configuration (W5-CONFIG).
 *
 * <p>Parsed from windypit.yml on the server classpath. Every field has a safe default so a
 * missing or partial file always yields a valid configuration (compat/primary/true).</p>
 *
 * <p>Development and canary default: mode=compat, arena-owner=primary. The release build switches
 * to region/dedicated only after every owner-thread, runtime and stress gate passes.</p>
 *
 * <p>Mechanism pruning has a global default per mechanism plus optional per-world overrides. The
 * override merge rule is: explicit world value, else explicit global value, else the built-in
 * default (true = prune). Unknown keys are recorded in {@link #warnings()} and ignored; a corrupt
 * value falls back to the default instead of deleting a world mechanism.</p>
 */
public final class WindyPitConfig {

    public enum ThreadingMode {
        COMPAT,
        REGION
    }

    public enum ArenaOwner {
        PRIMARY,
        DEDICATED
    }

    public static final class ThreadingConfig {
        private final ThreadingMode mode;
        private final ArenaOwner arenaOwner;
        private final int regionThreads;
        private final String computeThreads;

        ThreadingConfig(ThreadingMode mode, ArenaOwner arenaOwner, int regionThreads, String computeThreads) {
            this.mode = mode;
            this.arenaOwner = arenaOwner;
            this.regionThreads = regionThreads;
            this.computeThreads = computeThreads;
        }

        public ThreadingMode mode() { return mode; }
        public ArenaOwner arenaOwner() { return arenaOwner; }
        public int regionThreads() { return regionThreads; }
        public String computeThreads() { return computeThreads; }

        /** Resolves "auto" to the given default; numeric strings pass through. */
        public int resolveThreads(String value, int autoDefault) {
            if (value == null || "auto".equalsIgnoreCase(value.trim())) {
                return autoDefault;
            }
            try {
                int n = Integer.parseInt(value.trim());
                return n > 0 ? n : autoDefault;
            } catch (NumberFormatException e) {
                return autoDefault;
            }
        }
    }

    public static final class FeaturesConfig {
        private final boolean asyncEntityTracking;
        private final boolean mechanismPruning;

        FeaturesConfig(boolean asyncEntityTracking, boolean mechanismPruning) {
            this.asyncEntityTracking = asyncEntityTracking;
            this.mechanismPruning = mechanismPruning;
        }

        public boolean asyncEntityTracking() { return asyncEntityTracking; }
        public boolean mechanismPruning() { return mechanismPruning; }
    }

    public static final class RollbackConfig {
        private final boolean forcePrimaryThreadArena;
        private final boolean disableAsyncTracking;
        private final boolean disableRegionScheduler;

        RollbackConfig(boolean forcePrimaryThreadArena, boolean disableAsyncTracking, boolean disableRegionScheduler) {
            this.forcePrimaryThreadArena = forcePrimaryThreadArena;
            this.disableAsyncTracking = disableAsyncTracking;
            this.disableRegionScheduler = disableRegionScheduler;
        }

        public boolean forcePrimaryThreadArena() { return forcePrimaryThreadArena; }
        public boolean disableAsyncTracking() { return disableAsyncTracking; }
        public boolean disableRegionScheduler() { return disableRegionScheduler; }
    }

    /** Per-mechanism pruning defaults plus per-world overrides. */
    public static final class PruningConfig {
        public static final String NATURAL_SPAWNING = "natural-spawning";
        public static final String HOSTILE_MOB_SPAWNING = "hostile-mob-spawning";
        public static final String WEATHER_AND_DAYLIGHT = "weather-and-daylight";
        public static final String FARMING_AND_FURNACES = "farming-and-furnaces";
        public static final String VILLAGERS = "villagers";
        public static final String REDSTONE_AND_HOPPERS = "redstone-and-hoppers";
        public static final String RANDOM_TICK_NO_PLAYER_REGIONS = "random-tick-no-player-regions";
        public static final String NON_ARENA_ENTITY_MAINTENANCE = "non-arena-entity-maintenance";

        public static final Set<String> KNOWN_KEYS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                NATURAL_SPAWNING, HOSTILE_MOB_SPAWNING, WEATHER_AND_DAYLIGHT, FARMING_AND_FURNACES,
                VILLAGERS, REDSTONE_AND_HOPPERS, RANDOM_TICK_NO_PLAYER_REGIONS, NON_ARENA_ENTITY_MAINTENANCE)));

        private final Map<String, Boolean> global;
        private final Map<String, Map<String, Boolean>> worlds;
        private final List<String> arenaWorlds;

        PruningConfig(Map<String, Boolean> global, Map<String, Map<String, Boolean>> worlds, List<String> arenaWorlds) {
            this.global = Collections.unmodifiableMap(new LinkedHashMap<String, Boolean>(global));
            final Map<String, Map<String, Boolean>> copy = new LinkedHashMap<String, Map<String, Boolean>>();
            for (Map.Entry<String, Map<String, Boolean>> e : worlds.entrySet()) {
                copy.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<String, Boolean>(e.getValue())));
            }
            this.worlds = Collections.unmodifiableMap(copy);
            this.arenaWorlds = Collections.unmodifiableList(new ArrayList<String>(arenaWorlds));
        }

        /** Effective value: world override, else global explicit, else default true. */
        public boolean isDisabled(String worldName, String key) {
            if (worldName != null) {
                final Map<String, Boolean> override = worlds.get(worldName);
                if (override != null && override.containsKey(key)) {
                    return override.get(key).booleanValue();
                }
            }
            final Boolean value = global.get(key);
            return value == null || value.booleanValue();
        }

        public boolean hasWorldOverride(String worldName) {
            return worldName != null && worlds.containsKey(worldName);
        }

        public Set<String> overriddenWorlds() {
            return worlds.keySet();
        }

        public List<String> arenaWorlds() {
            return arenaWorlds;
        }

        public boolean isArenaWorld(String worldName) {
            return worldName != null && arenaWorlds.contains(worldName);
        }
    }

    private final ThreadingConfig threading;
    private final FeaturesConfig features;
    private final RollbackConfig rollback;
    private final PruningConfig pruning;
    private final List<String> warnings;

    private WindyPitConfig(ThreadingConfig threading, FeaturesConfig features, RollbackConfig rollback,
                           PruningConfig pruning, List<String> warnings) {
        this.threading = threading;
        this.features = features;
        this.rollback = rollback;
        this.pruning = pruning;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public ThreadingConfig threading() { return threading; }
    public FeaturesConfig features() { return features; }
    public RollbackConfig rollback() { return rollback; }
    public PruningConfig pruning() { return pruning; }

    /** Non-fatal configuration problems (unknown pruning keys, invalid values). */
    public List<String> warnings() { return warnings; }

    public static WindyPitConfig defaults() {
        return new WindyPitConfig(
            new ThreadingConfig(ThreadingMode.REGION, ArenaOwner.PRIMARY, 4, "auto"),
            new FeaturesConfig(true, true),
            new RollbackConfig(false, false, false),
            new PruningConfig(new LinkedHashMap<String, Boolean>(), new LinkedHashMap<String, Map<String, Boolean>>(),
                    Collections.singletonList("world")),
            Collections.<String>emptyList()
        );
    }

    /** Loads from a YAML file; missing file returns defaults. */
    public static WindyPitConfig load(File file) throws IOException {
        if (!file.exists()) {
            return defaults();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file); } catch (InvalidConfigurationException e) { throw new IOException("invalid windypit.yml", e); }
        return fromYaml(yaml);
    }

    /**
     * Parses from the already-loaded server configuration. This is the production entry:
     * {@code WindSpigotConfig} owns the physical windypit.yml (single read, single
     * copyDefaults write-back), and this method only derives the WindyPit section from
     * that same in-memory document — no second file read, no second write, so unknown
     * keys keep round-tripping through the owner.
     */
    public static WindyPitConfig fromConfiguration(org.bukkit.configuration.ConfigurationSection yaml) {
        if (yaml == null) {
            return defaults();
        }
        return fromYaml(yaml);
    }

    /** Parses from a YAML string; used by tests and callers holding a Reader. */
    public static WindyPitConfig parse(String yamlText) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(yamlText); } catch (InvalidConfigurationException e) { throw new IOException("invalid windypit.yml", e); }
        return fromYaml(yaml);
    }

    private static WindyPitConfig fromYaml(org.bukkit.configuration.ConfigurationSection yaml) {
        ThreadingMode mode = parseEnum(yaml, "threading.mode", ThreadingMode.REGION);
        ArenaOwner arenaOwner = parseEnum(yaml, "threading.arena-owner", ArenaOwner.PRIMARY);
        int regionThreads = yaml.getInt("threading.region-threads", 4);
        String computeThreads = yaml.getString("threading.compute-threads", "auto");

        ThreadingConfig threading = new ThreadingConfig(mode, arenaOwner,
                Math.max(1, regionThreads), computeThreads);

        FeaturesConfig features = new FeaturesConfig(
                yaml.getBoolean("features.async-entity-tracking", true),
                yaml.getBoolean("features.mechanism-pruning", true)
        );

        RollbackConfig rollback = new RollbackConfig(
                yaml.getBoolean("rollback.force-primary-thread-arena", false),
                yaml.getBoolean("rollback.disable-async-tracking", false),
                yaml.getBoolean("rollback.disable-region-scheduler", false)
        );

        final List<String> warnings = new ArrayList<String>();
        final PruningConfig pruning = parsePruning(yaml, warnings);

        return new WindyPitConfig(threading, features, rollback, pruning, warnings);
    }

    private static PruningConfig parsePruning(org.bukkit.configuration.ConfigurationSection yaml, List<String> warnings) {
        final Map<String, Boolean> global = new LinkedHashMap<String, Boolean>();
        for (String key : PruningConfig.KNOWN_KEYS) {
            final String path = "pruning." + key;
            if (yaml.contains(path)) {
                if (yaml.isBoolean(path)) {
                    global.put(key, Boolean.valueOf(yaml.getBoolean(path)));
                } else {
                    warnings.add("pruning." + key + " is not a boolean; using default true");
                }
            }
        }
        final ConfigurationSection pruningSection = yaml.getConfigurationSection("pruning");
        if (pruningSection != null) {
            for (String key : pruningSection.getKeys(false)) {
                if (!PruningConfig.KNOWN_KEYS.contains(key)
                        && !"worlds".equals(key) && !"arena-worlds".equals(key)) {
                    warnings.add("unknown pruning key 'pruning." + key + "' ignored");
                }
            }
        }

        final Map<String, Map<String, Boolean>> worlds = new LinkedHashMap<String, Map<String, Boolean>>();
        final ConfigurationSection worldsSection = yaml.getConfigurationSection("pruning.worlds");
        if (worldsSection != null) {
            for (String worldName : worldsSection.getKeys(false)) {
                final ConfigurationSection worldSection = worldsSection.getConfigurationSection(worldName);
                if (worldSection == null) {
                    warnings.add("pruning.worlds." + worldName + " is not a section; ignored");
                    continue;
                }
                final Map<String, Boolean> values = new LinkedHashMap<String, Boolean>();
                for (String key : worldSection.getKeys(false)) {
                    if (!PruningConfig.KNOWN_KEYS.contains(key)) {
                        warnings.add("unknown pruning key 'pruning.worlds." + worldName + "." + key + "' ignored");
                        continue;
                    }
                    if (worldSection.isBoolean(key)) {
                        values.put(key, Boolean.valueOf(worldSection.getBoolean(key)));
                    } else {
                        warnings.add("pruning.worlds." + worldName + "." + key
                                + " is not a boolean; world falls back to the global value");
                    }
                }
                worlds.put(worldName, values);
            }
        }

        List<String> arenaWorlds = new ArrayList<String>();
        final List<?> rawArena = yaml.getList("pruning.arena-worlds");
        if (rawArena != null) {
            for (Object value : rawArena) {
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    arenaWorlds.add(((String) value).trim());
                } else {
                    warnings.add("pruning.arena-worlds contains a non-string entry; ignored");
                }
            }
        }
        if (arenaWorlds.isEmpty()) {
            arenaWorlds.add("world");
        }
        return new PruningConfig(global, worlds, arenaWorlds);
    }

    private static <T extends Enum<T>> T parseEnum(org.bukkit.configuration.ConfigurationSection yaml, String path, T defaultValue) {
        String raw = yaml.getString(path, defaultValue.name());
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return T.valueOf(defaultValue.getDeclaringClass(), raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }
}
