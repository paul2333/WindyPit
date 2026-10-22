package com.windpvp.windspigot.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Prompt "single schema owner": WindSpigotConfig owns the physical windypit.yml (one read,
 * one copyDefaults write-back); WindyPitConfig must derive its section from that same
 * in-memory document. Unknown keys and legacy settings must round-trip untouched, and an
 * old user file must never be overwritten with defaults.
 */
public class WindyPitConfigSingleOwnerTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File write(String name, String content) throws IOException {
        File file = new File(temporary.getRoot(), name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Simulates WindSpigotConfig.init: load once, copyDefaults, save once. */
    private static YamlConfiguration ownerLoadAndRewrite(File file) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        config.options().copyDefaults(true);
        config.save(file);
        return config;
    }

    @Test
    public void windypitSectionIsDerivedFromTheOwnerDocumentWithoutASecondRead() throws Exception {
        File file = write("windypit.yml",
                "config-version: 29\n"
                        + "settings:\n"
                        + "  statistics: false\n"
                        + "threading:\n"
                        + "  mode: region\n"
                        + "  arena-owner: primary\n"
                        + "  region-threads: 4\n"
                        + "features:\n"
                        + "  mechanism-pruning: false\n"
                        + "pruning:\n"
                        + "  arena-worlds:\n"
                        + "    - world\n"
                        + "    - arena\n");

        YamlConfiguration owner = ownerLoadAndRewrite(file);
        WindyPitConfig config = WindyPitConfig.fromConfiguration(owner);

        assertEquals(WindyPitConfig.ThreadingMode.REGION, config.threading().mode());
        assertEquals(4, config.threading().regionThreads());
        assertFalse(config.features().mechanismPruning());
        assertEquals(java.util.List.of("world", "arena"), config.pruning().arenaWorlds());
        assertTrue("no warnings for a well-formed file: " + config.warnings(),
                config.warnings().isEmpty());
    }

    @Test
    public void unknownKeysSurviveTheOwnerRewrite() throws Exception {
        File file = write("windypit.yml",
                "config-version: 29\n"
                        + "settings:\n"
                        + "  statistics: false\n"
                        + "  operator-custom-knob: 7\n"
                        + "threading:\n"
                        + "  mode: compat\n"
                        + "threading-custom: keep-me\n"
                        + "pruning:\n"
                        + "  unknown-mechanism: true\n");

        ownerLoadAndRewrite(file);

        YamlConfiguration reloaded = new YamlConfiguration();
        reloaded.load(file);
        assertEquals("operator knobs must round-trip", 7, reloaded.getInt("settings.operator-custom-knob"));
        assertEquals("keep-me", reloaded.getString("threading-custom"));
        assertTrue("unknown pruning keys must stay on disk", reloaded.contains("pruning.unknown-mechanism"));

        // The parser reports the unknown pruning key instead of silently dropping it.
        WindyPitConfig config = WindyPitConfig.fromConfiguration(reloaded);
        assertTrue(config.warnings().stream().anyMatch(w -> w.contains("unknown pruning key")));
    }

    @Test
    public void legacyUserFileIsNeverOverwrittenWithDefaults() throws Exception {
        // An old install: settings.* only, plus keys the new schema added later.
        File file = write("windspigot.yml",
                "config-version: 12\n"
                        + "settings:\n"
                        + "  statistics: true\n"
                        + "  hit-delay: 8\n"
                        + "threading:\n"
                        + "  mode: region\n"
                        + "  region-threads: 2\n");

        ownerLoadAndRewrite(file);

        YamlConfiguration reloaded = new YamlConfiguration();
        reloaded.load(file);
        assertTrue("legacy toggle preserved", reloaded.getBoolean("settings.statistics"));
        assertEquals("legacy tuning preserved", 8, reloaded.getInt("settings.hit-delay"));
        assertEquals("new-schema value preserved", "region", reloaded.getString("threading.mode"));
        assertEquals(2, reloaded.getInt("threading.region-threads"));
    }

    @Test
    public void missingFileFallsBackToDefaults() {
        WindyPitConfig config = WindyPitConfig.fromConfiguration(null);
        // 2026-10-22: shipped defaults are REGION with one owner thread so a fresh install
        // gets off-thread visibility planning instead of silently running single-threaded.
        assertEquals(WindyPitConfig.ThreadingMode.REGION, config.threading().mode());
        assertEquals(1, config.threading().regionThreads());
    }
}
