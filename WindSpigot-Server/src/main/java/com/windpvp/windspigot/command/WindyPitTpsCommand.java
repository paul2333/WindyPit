
package com.windpvp.windspigot.command;

import com.windpvp.windspigot.scheduler.ComputePool;
import com.windpvp.windspigot.scheduler.SchedulerDiagnostics;
import com.windpvp.windspigot.scheduler.WindyPitTickIntegration;
import com.windpvp.windspigot.scheduler.WorldRegionRuntime;
import net.minecraft.server.MinecraftServer;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /windypit-tps command: displays real scheduler diagnostics (W1-D).
 * Data sources: SchedulerDiagnostics, ComputePool.Stats, queue depths, reject counts, stale-drop,
 * active thread names, tick quantiles. Missing sources show "n/a" (never fabricated).
 */
public class WindyPitTpsCommand extends Command {

    public WindyPitTpsCommand(String name) {
        super(name);
        this.description = "WindyPit scheduler diagnostics";
        this.usageMessage = "/windypit-tps";
        this.setPermission("bukkit.command.windypit-tps");
    }

    @Override
    public boolean execute(CommandSender sender, String currentAlias, String[] args) {
        if (!testPermission(sender)) {
            return true;
        }

        MinecraftServer server = MinecraftServer.getServer();
        WindyPitTickIntegration windyTick = server.getWindyTick();

        if (windyTick == null) {
            sender.sendMessage(ChatColor.RED + "WindyPit scheduler not initialized");
            return true;
        }

        sender.sendMessage(ChatColor.DARK_AQUA + "WindyPit Scheduler Diagnostics:");

        // Config mode
        sender.sendMessage(ChatColor.AQUA + "Threading mode: " + ChatColor.GREEN
                + windyTick.config().threading().mode()
                + ChatColor.GRAY + " | " + ChatColor.AQUA + "Arena owner: " + ChatColor.GREEN
                + windyTick.config().threading().arenaOwner());

        // 【2026-10-22】Bukkit 异步池：饱和时所有 runTaskAsynchronously 会永久排队，
        // 而且不抛异常、不打日志（真实事故：音乐播放器占满后玩家卡「正在加载档案」）。
        // 这一行是现场唯一能直接看到它的地方。
        //
        // 【必须用反射，不能 instanceof】源码里写的是 org.bukkit.craftbukkit.scheduler.CraftScheduler，
        // 但打包时 CraftBukkit 会把包名重映射成 org.bukkit.craftbukkit.v1_8_R3.scheduler.*
        // ⇒ 直接 instanceof 会加载一个运行时不存在的类，抛 NoClassDefFoundError 后被 catch 吞掉，
        // 这一行就永远不打印（实测确认过产物里的路径带 v1_8_R3）。
        try {
            final Object scheduler = org.bukkit.Bukkit.getScheduler();
            final java.lang.reflect.Method summary = scheduler.getClass().getMethod("asyncPoolSummary");
            final java.lang.reflect.Method saturated = scheduler.getClass().getMethod("asyncPoolSaturated");
            final boolean poolSaturated = Boolean.TRUE.equals(saturated.invoke(scheduler));
            sender.sendMessage(ChatColor.AQUA + "Bukkit async pool: "
                    + (poolSaturated ? ChatColor.RED : ChatColor.GREEN)
                    + summary.invoke(scheduler));
        } catch (Throwable unavailable) {
            // 非 WindSpigot 核心或方法缺失：诊断读不到不影响其余输出
            sender.sendMessage(ChatColor.AQUA + "Bukkit async pool: " + ChatColor.GRAY + "n/a");
        }

        // Live TPS / MSPT (real server counters, never fabricated)
        final double tps1 = server.recentTps.length > 0 ? server.recentTps[0] : 20.0D;
        final double tps5 = server.recentTps.length > 1 ? server.recentTps[1] : tps1;
        final double tps15 = server.recentTps.length > 2 ? server.recentTps[2] : tps1;
        sender.sendMessage(ChatColor.AQUA + "TPS: " + ChatColor.GREEN
                + String.format(java.util.Locale.ROOT, "1m=%.2f 5m=%.2f 15m=%.2f", tps1, tps5, tps15)
                + ChatColor.GRAY + " | " + ChatColor.AQUA + "MSPT: " + ChatColor.GREEN
                + String.format(java.util.Locale.ROOT, "%.2f", server.getLastMspt()));

        // Seam count and pipeline segments
        sender.sendMessage(ChatColor.AQUA + "Tick seams: " + ChatColor.GREEN + windyTick.seamCount()
                + ChatColor.GRAY + " | " + ChatColor.AQUA + "Pipeline segments: " + ChatColor.GREEN
                + windyTick.pipeline().segmentCount()
                + ChatColor.GRAY + " | " + ChatColor.AQUA + "Pipeline ticks: " + ChatColor.GREEN
                + windyTick.pipeline().tickCount());

        // Global scheduler stats
        sender.sendMessage(ChatColor.AQUA + "Global scheduler: " + ChatColor.GREEN
                + "immediate=" + windyTick.globalScheduler().immediateDepth()
                + " delayed=" + windyTick.globalScheduler().delayedDepth()
                + " closed=" + windyTick.globalScheduler().isClosed());

        // Region scheduler stats
        List<SchedulerDiagnostics.RegionStats> regionStats = windyTick.regionScheduler().diagnostics().snapshotRegionStats();
        if (regionStats.isEmpty()) {
            sender.sendMessage(ChatColor.AQUA + "Region scheduler: " + ChatColor.GRAY + "n/a");
        } else {
            for (SchedulerDiagnostics.RegionStats rs : regionStats) {
                sender.sendMessage(ChatColor.AQUA + "  Region " + ChatColor.YELLOW + rs.regionId() + ChatColor.AQUA + ": "
                        + ChatColor.GREEN + "scheduled=" + rs.scheduled()
                        + " executed=" + rs.executed()
                        + " rejected=" + rs.rejected()
                        + " retired=" + rs.retired()
                        + " maxDepth=" + rs.maxQueueDepth());
            }
        }

        // Compute pool stats
        ComputePool.Stats computeStats = windyTick.computeStats();
        if (computeStats == null) {
            sender.sendMessage(ChatColor.AQUA + "Compute pool: " + ChatColor.GRAY + "n/a");
        } else {
            sender.sendMessage(ChatColor.AQUA + "Compute pool: " + ChatColor.GREEN
                    + "submitted=" + computeStats.submitted()
                    + " executed=" + computeStats.executed()
                    + " failed=" + computeStats.failed()
                    + " rejected=" + computeStats.rejected()
                    + " staleDropped=" + computeStats.staleDropped()
                    + " deliveryRejected=" + computeStats.deliveryRejected()
                    + " queued=" + windyTick.computePool().queuedCount());
        }

        // Snapshot generation
        sender.sendMessage(ChatColor.AQUA + "Snapshot generation: " + ChatColor.GREEN
                + windyTick.snapshotGeneration()
                + ChatColor.GRAY + " | " + ChatColor.AQUA + "Compute submitted: " + ChatColor.GREEN
                + windyTick.computeSubmittedCount()
                + ChatColor.GRAY + " | " + ChatColor.AQUA + "Compute completed: " + ChatColor.GREEN
                + windyTick.computeCompletedCount()
                + ChatColor.GRAY + " | " + ChatColor.AQUA + "Stale rejected: " + ChatColor.GREEN
                + windyTick.computeStaleRejectedCount());

        // Per-world owner domains (REGION mode only; real production planning counters)
        final WorldRegionRuntime worldRegions = windyTick.worldRegions();
        if (worldRegions == null) {
            sender.sendMessage(ChatColor.AQUA + "World owner domains: " + ChatColor.GRAY
                    + "disabled (COMPAT mode / async tracking off)");
        } else {
            sender.sendMessage(ChatColor.AQUA + "World owner domains: " + ChatColor.GREEN
                    + worldRegions.domainCount() + ChatColor.GRAY + " | " + ChatColor.AQUA
                    + "applied=" + worldRegions.appliedTotal()
                    + " dropped=" + worldRegions.droppedTotal()
                    + " rejected=" + worldRegions.rejectedTotal()
                    + " failed=" + worldRegions.failedTotal());
            for (WorldRegionRuntime.WorldStats ws : worldRegions.stats()) {
                sender.sendMessage(ChatColor.AQUA + "  World " + ChatColor.YELLOW + ws.worldName()
                        + ChatColor.AQUA + " [" + ws.regionId() + "]: " + ChatColor.GREEN
                        + "submitted=" + ws.submitted()
                        + " planned=" + ws.planned()
                        + " applied=" + ws.applied()
                        + " dropped=" + ws.dropped()
                        + " rejected=" + ws.rejected()
                        + " failed=" + ws.failed()
                        + " queue=" + ws.queueDepth()
                        + " owner=" + (worldRegions.ownerThreadName(ws.worldName()) != null
                                ? worldRegions.ownerThreadName(ws.worldName()) : "starting"));
            }
        }

        // Per-world tick cost (measured in WorldTicker, never estimated)
        sender.sendMessage(ChatColor.AQUA + "World tick cost:");
        for (net.minecraft.server.WorldServer world : server.worlds) {
            if (world == null) {
                continue;
            }
            final com.windpvp.windspigot.world.WorldTicker ticker = world.ticker;
            if (ticker == null) {
                continue;
            }
            sender.sendMessage(ChatColor.AQUA + "  " + ChatColor.YELLOW + world.getWorld().getName()
                    + ChatColor.AQUA + ": last=" + ChatColor.GREEN
                    + String.format(java.util.Locale.ROOT, "%.2fms", ticker.lastTickMillis())
                    + " p50=" + String.format(java.util.Locale.ROOT, "%.2fms", ticker.tickPercentileMillis(50))
                    + " p95=" + String.format(java.util.Locale.ROOT, "%.2fms", ticker.tickPercentileMillis(95))
                    + " p99=" + String.format(java.util.Locale.ROOT, "%.2fms", ticker.tickPercentileMillis(99))
                    + ChatColor.GRAY + " (samples ring)");
        }

        // Per-world owner domains (REGION mode only; real production planning counters)
        // Active thread names (region threads only if in dedicated mode)
        String arenaThread = windyTick.regionScheduler().ownerThreadName(windyTick.arena());
        sender.sendMessage(ChatColor.AQUA + "Arena owner thread: " + ChatColor.GREEN
                + (arenaThread != null ? arenaThread : "n/a (pumped on primary)"));

        appendThreadPanel(sender, windyTick);

        return true;
    }

    /**
     * Multi-threading effectiveness panel: proves whether the WindyPit kernel is actually running
     * off the primary thread, and how many cores each thread group consumes.
     */
    private void appendThreadPanel(CommandSender sender, WindyPitTickIntegration windyTick) {
        final ThreadCpuSampler sampler = ThreadCpuSampler.get();
        sampler.start();
        ThreadCpuSampler.Snapshot snapshot = sampler.latest();
        if (snapshot.isEmpty()) {
            snapshot = sampler.sampleBlocking(300L);
        }

        sender.sendMessage(ChatColor.DARK_AQUA + "Multithreading:");
        final String mode = String.valueOf(windyTick.config().threading().mode());
        final WorldRegionRuntime regions = windyTick.worldRegions();
        final int domains = regions == null ? 0 : regions.domainCount();
        final boolean regionActive = regions != null && domains > 0;

        final String verdict;
        if ("COMPAT".equals(mode)) {
            verdict = ChatColor.GRAY + "NOT ACTIVE (COMPAT: all work on the primary thread)";
        } else if (!regionActive) {
            verdict = ChatColor.YELLOW + "NOT ACTIVE (REGION mode but no owner domain)";
        } else {
            verdict = ChatColor.GREEN + "ACTIVE (" + domains + " owner domains on dedicated threads)";
        }
        sender.sendMessage(ChatColor.AQUA + "  Mode: " + ChatColor.GREEN + mode
                + ChatColor.GRAY + " | " + ChatColor.AQUA + "Effective: " + verdict);

        if (!sampler.isAvailable() || snapshot.isEmpty()) {
            sender.sendMessage(ChatColor.AQUA + "  CPU: " + ChatColor.GRAY
                    + (sampler.isAvailable() ? "sampling (available after 1s)" : "n/a (JVM lacks per-thread CPU time)"));
            return;
        }

        final int processors = snapshot.processors();
        sender.sendMessage(ChatColor.AQUA + "  CPUs: " + ChatColor.GREEN + processors
                + ChatColor.GRAY + " | " + ChatColor.AQUA + "JVM threads: " + ChatColor.GREEN + snapshot.samples().size()
                + ChatColor.GRAY + " | " + ChatColor.AQUA + "total: " + ChatColor.GREEN
                + String.format(java.util.Locale.ROOT, "%.2f cores (%.0f%%)",
                        snapshot.totalCores(), snapshot.totalCores() * 100.0D / processors));

        // Group by WindyPit thread family so the panel answers "did it actually split work?"
        final java.util.Map<String, double[]> groups = new java.util.LinkedHashMap<String, double[]>();
        final java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<String, Integer>();
        for (ThreadCpuSampler.Sample sample : snapshot.samples()) {
            final String group = threadGroup(sample.name());
            double[] acc = groups.get(group);
            if (acc == null) {
                acc = new double[1];
                groups.put(group, acc);
                counts.put(group, Integer.valueOf(0));
            }
            acc[0] += sample.cpuPercent();
            counts.put(group, Integer.valueOf(counts.get(group).intValue() + 1));
        }

        sender.sendMessage(ChatColor.AQUA + "  Per-group CPU (1s window):");
        for (java.util.Map.Entry<String, double[]> entry : groups.entrySet()) {
            final double percent = entry.getValue()[0];
            final int count = counts.get(entry.getKey()).intValue();
            if (percent < 0.05D && count > 0 && "other".equals(entry.getKey())) {
                continue;
            }
            sender.sendMessage(ChatColor.AQUA + "    " + ChatColor.YELLOW
                    + String.format(java.util.Locale.ROOT, "%-22s", entry.getKey())
                    + ChatColor.GREEN + String.format(java.util.Locale.ROOT, "%6.1f%%", percent)
                    + ChatColor.GRAY + " | " + ChatColor.AQUA
                    + String.format(java.util.Locale.ROOT, "%.2f cores", percent / 100.0D)
                    + ChatColor.GRAY + " | " + count + " threads");
        }

        // Top consumers prove which thread is actually doing the work.
        final java.util.List<ThreadCpuSampler.Sample> top =
                new java.util.ArrayList<ThreadCpuSampler.Sample>(snapshot.samples());
        java.util.Collections.sort(top, new java.util.Comparator<ThreadCpuSampler.Sample>() {
            @Override
            public int compare(ThreadCpuSampler.Sample a, ThreadCpuSampler.Sample b) {
                return Double.compare(b.cpuPercent(), a.cpuPercent());
            }
        });
        sender.sendMessage(ChatColor.AQUA + "  Top threads:");
        for (int i = 0; i < top.size() && i < 6; i++) {
            final ThreadCpuSampler.Sample sample = top.get(i);
            sender.sendMessage(ChatColor.AQUA + "    " + ChatColor.YELLOW
                    + String.format(java.util.Locale.ROOT, "%-28s", trim(sample.name(), 28))
                    + ChatColor.GREEN + String.format(java.util.Locale.ROOT, "%6.1f%%", sample.cpuPercent())
                    + ChatColor.GRAY + " | " + ChatColor.AQUA
                    + String.format(java.util.Locale.ROOT, "%.2f cores", sample.cores())
                    + ChatColor.GRAY + " | " + sample.state());
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "?";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "~";
    }

    private static String threadGroup(String name) {
        if (name == null) {
            return "other";
        }
        if (name.startsWith("WindyPit-Region-")) {
            return "Region owner";
        }
        if (name.startsWith("WindyPit-Compute-")) {
            return "Compute pool";
        }
        if (name.startsWith("WindyPit-CpuSampler")) {
            return "sampler";
        }
        if (name.startsWith("WindyPit")) {
            return "WindyPit misc";
        }
        if ("Server thread".equals(name)) {
            return "Primary (Server thread)";
        }
        if (name.startsWith("Netty")) {
            return "Netty IO";
        }
        if (name.startsWith("Craft Scheduler") || name.startsWith("CraftAsync")) {
            return "Craft async";
        }
        if (name.startsWith("GC") || name.startsWith("C2 ") || name.startsWith("C1 ")
                || name.startsWith("VM ") || name.startsWith("Reference")) {
            return "JVM internal";
        }
        return "other";
    }
}

