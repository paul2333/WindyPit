package com.windpvp.windspigot.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

/**
 * Per-mechanism pruning configuration + real NMS decision-point wiring (W5-PRUNING).
 *
 * <p>Config resolution is tested directly. The NMS side is pinned by source contracts because a
 * full {@code WorldServer} cannot be constructed in a unit test; each assertion names the exact
 * production method that now consults the plan.</p>
 */
public class PruningConfigAndWiringTest {

    private static WindyPitConfig parse(String yaml) throws IOException {
        return WindyPitConfig.parse(yaml);
    }

    @Test
    public void globalKeysResolveAndWorldOverrideWins() throws IOException {
        WindyPitConfig config = parse(
                "features:\n  mechanism-pruning: true\n"
                        + "pruning:\n"
                        + "  farming-and-furnaces: true\n"
                        + "  redstone-and-hoppers: true\n"
                        + "  arena-worlds: [world, arena]\n"
                        + "  worlds:\n"
                        + "    world_nether:\n"
                        + "      farming-and-furnaces: false\n"
                        + "      redstone-and-hoppers: false\n");
        MechanismPruningPlan global = MechanismPruningPlan.fromConfig(config);
        assertTrue(global.disableFarmingAndFurnaces());
        assertTrue(global.disableRedstoneAndHoppers());
        MechanismPruningPlan nether = MechanismPruningPlan.fromConfig(config, "world_nether");
        assertFalse("world override must win", nether.disableFarmingAndFurnaces());
        assertFalse("world override must win", nether.disableRedstoneAndHoppers());
        assertTrue("unoverridden keys inherit the global value", nether.disableVillagers());
        assertTrue(config.pruning().isArenaWorld("world"));
        assertTrue(config.pruning().isArenaWorld("arena"));
        assertFalse(config.pruning().isArenaWorld("world_nether"));
    }

    @Test
    public void masterSwitchDisablesEveryMechanism() throws IOException {
        WindyPitConfig config = parse(
                "features:\n  mechanism-pruning: false\n"
                        + "pruning:\n  farming-and-furnaces: true\n");
        MechanismPruningPlan plan = MechanismPruningPlan.fromConfig(config, "world");
        assertFalse(plan.disableNaturalSpawning());
        assertFalse(plan.disableHostileMobSpawning());
        assertFalse(plan.disableWeatherAndDaylight());
        assertFalse(plan.disableFarmingAndFurnaces());
        assertFalse(plan.disableVillagers());
        assertFalse(plan.disableRedstoneAndHoppers());
        assertFalse(plan.disableRandomTickNoPlayerRegions());
        assertFalse(plan.disableNonArenaEntityMaintenance());
    }

    @Test
    public void unknownAndInvalidKeysAreDiagnosedWithoutDisablingMechanisms() throws IOException {
        WindyPitConfig config = parse(
                "pruning:\n"
                        + "  farming-and-furnaces: nope\n"
                        + "  made-up-key: true\n"
                        + "  worlds:\n"
                        + "    world:\n"
                        + "      also-made-up: false\n"
                        + "      villagers: false\n");
        List<String> warnings = config.warnings();
        assertTrue("invalid global value must be diagnosed", warnings.toString().contains("farming-and-furnaces"));
        assertTrue("unknown global key must be diagnosed", warnings.toString().contains("made-up-key"));
        assertTrue("unknown world key must be diagnosed", warnings.toString().contains("also-made-up"));
        // Fail-safe: the invalid value falls back to default true, the valid override applies.
        assertTrue(config.pruning().isDisabled("world", WindyPitConfig.PruningConfig.FARMING_AND_FURNACES));
        assertFalse(config.pruning().isDisabled("world", WindyPitConfig.PruningConfig.VILLAGERS));
    }

    @Test
    public void defaultConfigKeepsCurrentPruningBehaviour() {
        WindyPitConfig config = WindyPitConfig.defaults();
        for (String key : WindyPitConfig.PruningConfig.KNOWN_KEYS) {
            assertTrue("default must keep pruning enabled for " + key, config.pruning().isDisabled("world", key));
        }
        assertTrue(config.pruning().isArenaWorld("world"));
    }

    @Test
    public void everyPruningKeyIsReadAtARealNmsDecisionPoint() throws IOException {
        assertContains("net/minecraft/server/World.java",
                "if (this.pruningPlan.disableWeatherAndDaylight())");
        assertContains("net/minecraft/server/WorldServer.java",
                "pruningPlan.disableNaturalSpawning()");
        assertContains("net/minecraft/server/WorldServer.java",
                "pruningPlan.disableHostileMobSpawning()");
        assertContains("net/minecraft/server/WorldServer.java",
                "pruningPlan.disableRandomTickNoPlayerRegions()");
        assertContains("net/minecraft/server/WorldServer.java",
                "pruningPlan.disableNonArenaEntityMaintenance()");
        assertContains("net/minecraft/server/WorldServer.java",
                "pruningPlan.disableRedstoneAndHoppers()");
        assertContains("net/minecraft/server/WorldServer.java",
                "pruningPlan.disableVillagers()");
        assertContains("net/minecraft/server/TileEntityFurnace.java",
                "disableFarmingAndFurnaces()");
        assertContains("net/minecraft/server/TileEntityHopper.java",
                "disableRedstoneAndHoppers()");
        assertContains("net/minecraft/server/BlockCrops.java",
                "disableFarmingAndFurnaces()");
        assertContains("net/minecraft/server/BlockStem.java",
                "disableFarmingAndFurnaces()");
        assertContains("net/minecraft/server/BlockNetherWart.java",
                "disableFarmingAndFurnaces()");
        assertContains("net/minecraft/server/BlockRedstoneWire.java",
                "disableRedstoneAndHoppers()");
        assertContains("net/minecraft/server/BlockRedstoneTorch.java",
                "disableRedstoneAndHoppers()");
    }

    @Test
    public void pvpCriticalPathsAreNotGatedByPruning() throws IOException {
        // Projectiles, potions, teleport and damage must not consult the pruning plan.
        assertNotContains("net/minecraft/server/EntityArrow.java", "pruningPlan");
        assertNotContains("net/minecraft/server/EntityPotion.java", "pruningPlan");
        assertNotContains("net/minecraft/server/EntityTNTPrimed.java", "pruningPlan");
        assertNotContains("net/minecraft/server/EntityPlayer.java", "pruningPlan");
        assertNotContains("net/minecraft/server/EntityDamageSourceIndirect.java", "pruningPlan");
    }

    private static void assertContains(String relative, String needle) throws IOException {
        assertTrue(relative + " must contain " + needle, read(relative).contains(needle));
    }

    private static void assertNotContains(String relative, String needle) throws IOException {
        assertFalse(relative + " must not contain " + needle, read(relative).contains(needle));
    }

    private static String read(String relative) throws IOException {
        final Path modulePath = Paths.get("src", "main", "java",
                relative.replace('/', java.io.File.separatorChar));
        final Path rootPath = Paths.get(relative.replace('/', java.io.File.separatorChar));
        Path directory = Paths.get("").toAbsolutePath();
        for (int level = 0; level < 8 && directory != null; level++) {
            for (Path candidatePath : new Path[]{directory.resolve(modulePath), directory.resolve(rootPath)}) {
                if (Files.isRegularFile(candidatePath)) {
                    return new String(Files.readAllBytes(candidatePath), StandardCharsets.UTF_8);
                }
            }
            directory = directory.getParent();
        }
        throw new AssertionError("source not found: " + relative);
    }
}
