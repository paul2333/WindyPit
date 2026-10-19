
package com.windpvp.windspigot.scheduler;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class WindyPitDiagnosticsTest {

    @Test
    public void tpsIs20WhenAllTicksAtInterval() {
        long[] durations = {50_000_000L, 50_000_000L, 50_000_000L};
        assertEquals(20.0D, WindyPitDiagnostics.computeTps(durations, 3, 50_000_000L), 0.01);
    }

    @Test
    public void tpsDropsWhenTicksExceedInterval() {
        long[] durations = {100_000_000L, 100_000_000L, 100_000_000L};
        double tps = WindyPitDiagnostics.computeTps(durations, 3, 50_000_000L);
        assertTrue("tps should be ~10 when ticks take 2x interval, got " + tps, tps < 10.5 && tps > 9.5);
    }

    @Test
    public void tpsClampsAt20WhenTicksAreFast() {
        long[] durations = {10_000_000L, 10_000_000L, 10_000_000L};
        assertEquals(20.0D, WindyPitDiagnostics.computeTps(durations, 3, 50_000_000L), 0.01);
    }

    @Test
    public void tpsDefaultsTo20WithNoSamples() {
        assertEquals(20.0D, WindyPitDiagnostics.computeTps(new long[0], 0, 50_000_000L), 0.01);
    }

    @Test
    public void snapshotCarriesAllFields() {
        WindyPitDiagnostics.RegionReport rr = new WindyPitDiagnostics.RegionReport("arena", 100, 95, 3, 2, 42);
        WindyPitDiagnostics.ComputeReport cr = new WindyPitDiagnostics.ComputeReport(50, 45, 2, 1, 1, 1, 0);
        WindyPitDiagnostics.NetworkReport nr = new WindyPitDiagnostics.NetworkReport(200, 5, 190, 8);

        WindyPitDiagnostics d = WindyPitDiagnostics.snapshot(1000L, 3, 19.5, 5.0, 25.0, 48.0,
                Collections.singletonList(rr), cr, nr);

        assertEquals(1000L, d.tickCount());
        assertEquals(3, d.threadViolations());
        assertEquals(19.5, d.tps(), 0.01);
        assertEquals(5.0, d.tickP50Millis(), 0.01);
        assertEquals(25.0, d.tickP95Millis(), 0.01);
        assertEquals(48.0, d.tickP99Millis(), 0.01);
        assertEquals(1, d.regions().size());
        assertEquals("arena", d.regions().get(0).regionId());
        assertEquals(100, d.regions().get(0).scheduled());
        assertEquals(50, d.compute().submitted());
        assertEquals(200, d.network().totalEnqueued());
        assertTrue(d.capturedAtNanos() > 0);
    }

    @Test
    public void regionsListIsImmutable() {
        WindyPitDiagnostics.RegionReport rr = new WindyPitDiagnostics.RegionReport("test", 1, 1, 0, 0, 1);
        WindyPitDiagnostics d = WindyPitDiagnostics.snapshot(0, 0, 20, 0, 0, 0,
                Collections.singletonList(rr), null, null);
        try {
            d.regions().add(rr);
            fail("regions list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}

