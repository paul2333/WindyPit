package com.windpvp.windspigot.performance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TickPerformanceMonitorTest {

    @Test
    public void tickWindowUsesOnlyMostRecentSamplesAndComputesInterpolatedPercentile() {
        TickPerformanceMonitor monitor = new TickPerformanceMonitor();
        monitor.recordTickDuration(100_000_000L);
        for (int millis = 1; millis <= 10; millis++) {
            monitor.recordTickDuration(millis * 1_000_000L);
        }

        TickPerformanceMonitor.TickStats stats = monitor.tickStats(10);

        assertEquals(1_000_000L, stats.getMinNanos());
        assertEquals(5_500_000D, stats.getMedianNanos(), 0.01D);
        assertEquals(9_550_000D, stats.getP95Nanos(), 0.01D);
        assertEquals(9_910_000D, stats.getP99Nanos(), 0.01D);
        assertEquals(10_000_000L, stats.getMaxNanos());
        assertEquals(10, stats.getSampleCount());
    }

    @Test
    public void tickWindowDoesNotClaimSamplesThatHaveNotBeenRecorded() {
        TickPerformanceMonitor monitor = new TickPerformanceMonitor();
        monitor.recordTickDuration(2_000_000L);

        TickPerformanceMonitor.TickStats stats = monitor.tickStats(200);

        assertEquals(1, stats.getSampleCount());
        assertEquals(2_000_000L, stats.getMinNanos());
        assertEquals(2_000_000D, stats.getMedianNanos(), 0.01D);
        assertEquals(2_000_000D, stats.getP95Nanos(), 0.01D);
        assertEquals(2_000_000D, stats.getP99Nanos(), 0.01D);
        assertEquals(2_000_000L, stats.getMaxNanos());
    }

    @Test
    public void cpuWindowsIgnoreUnavailableAndNonFiniteSamples() {
        TickPerformanceMonitor monitor = new TickPerformanceMonitor();
        monitor.recordCpuLoad(0.5D, 0.2D);
        monitor.recordCpuLoad(-1.0D, Double.NaN);
        monitor.recordCpuLoad(0.7D, 0.4D);

        TickPerformanceMonitor.CpuStats stats = monitor.cpuStats(10);

        assertTrue(stats.hasSystemLoad());
        assertEquals(60.0D, stats.getSystemPercent(), 0.0001D);
        assertTrue(stats.hasProcessLoad());
        assertEquals(30.0D, stats.getProcessPercent(), 0.0001D);
        assertTrue(monitor.cpuStats(1).hasProcessLoad());
        assertEquals(40.0D, monitor.cpuStats(1).getProcessPercent(), 0.0001D);
    }

    @Test
    public void durationsThatCannotRepresentACompletedTickAreIgnored() {
        TickPerformanceMonitor monitor = new TickPerformanceMonitor();
        monitor.recordTickDuration(0L);
        monitor.recordTickDuration(-1L);

        assertEquals(0, monitor.tickStats(200).getSampleCount());
    }

    @Test
    public void fixedCapacityWindowsRetainOnlyNewestSamples() {
        TickPerformanceMonitor monitor = new TickPerformanceMonitor(3, 2);
        monitor.recordTickDuration(1L);
        monitor.recordTickDuration(2L);
        monitor.recordTickDuration(3L);
        monitor.recordTickDuration(4L);

        TickPerformanceMonitor.TickStats stats = monitor.tickStats(10);

        assertEquals(3, stats.getSampleCount());
        assertEquals(2L, stats.getMinNanos());
        assertEquals(4L, stats.getMaxNanos());
    }

    @Test
    public void cpuWindowsAreBoundedAndUnavailableLoadsStayUnavailable() {
        TickPerformanceMonitor monitor = new TickPerformanceMonitor(2, 2);
        monitor.recordCpuLoad(0.1D, 0.2D);
        monitor.recordCpuLoad(0.3D, -1.0D);
        monitor.recordCpuLoad(0.5D, Double.NaN);

        TickPerformanceMonitor.CpuStats stats = monitor.cpuStats(10);

        assertEquals(40.0D, stats.getSystemPercent(), 0.0001D);
        assertFalse(stats.hasProcessLoad());
        assertTrue(Double.isNaN(stats.getProcessPercent()));
    }

    @Test
    public void cpuWindowSamplesMatchTheFiveSecondSamplingPeriod() {
        assertEquals(2, TickPerformanceMonitor.cpuWindowSamples(10));
        assertEquals(12, TickPerformanceMonitor.cpuWindowSamples(60));
        assertEquals(180, TickPerformanceMonitor.cpuWindowSamples(15 * 60));
        assertEquals(1, TickPerformanceMonitor.cpuWindowSamples(1));
    }
}
