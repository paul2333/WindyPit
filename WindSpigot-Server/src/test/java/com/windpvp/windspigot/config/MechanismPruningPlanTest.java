
package com.windpvp.windspigot.config;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class MechanismPruningPlanTest {

    @Test
    public void pruningEnabledAllTogglesTrue() throws IOException {
        WindyPitConfig cfg = WindyPitConfig.parse("features:\n  mechanism-pruning: true\n");
        MechanismPruningPlan plan = MechanismPruningPlan.fromConfig(cfg);
        assertTrue(plan.disableNaturalSpawning());
        assertTrue(plan.disableHostileMobSpawning());
        assertTrue(plan.disableWeatherAndDaylight());
        assertTrue(plan.disableFarmingAndFurnaces());
        assertTrue(plan.disableVillagers());
        assertTrue(plan.disableRedstoneAndHoppers());
        assertTrue(plan.disableRandomTickNoPlayerRegions());
        assertTrue(plan.disableNonArenaEntityMaintenance());
    }

    @Test
    public void pruningDisabledAllTogglesFalse() throws IOException {
        WindyPitConfig cfg = WindyPitConfig.parse("features:\n  mechanism-pruning: false\n");
        MechanismPruningPlan plan = MechanismPruningPlan.fromConfig(cfg);
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
    public void defaultsEnablePruning() {
        WindyPitConfig defaults = WindyPitConfig.defaults();
        MechanismPruningPlan plan = MechanismPruningPlan.fromConfig(defaults);
        assertTrue("defaults should enable pruning", plan.disableNaturalSpawning());
    }

    @Test
    public void builderSupportsPartialToggles() {
        MechanismPruningPlan plan = MechanismPruningPlan.builder()
                .disableNaturalSpawning(true)
                .disableWeatherAndDaylight(true)
                .disableRedstoneAndHoppers(false)
                .build();
        assertTrue(plan.disableNaturalSpawning());
        assertTrue(plan.disableWeatherAndDaylight());
        assertFalse(plan.disableRedstoneAndHoppers());
        assertFalse(plan.disableVillagers());
    }
}

