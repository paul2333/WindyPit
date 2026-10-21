package org.spigotmc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import com.falchus.spigot.optimizations.FastNetworkManager;
import com.windpvp.windspigot.performance.TickPerformanceMonitor;
import com.windpvp.windspigot.async.BoundedThreadPoolExecutor;
import com.windpvp.windspigot.async.pathsearch.SearchHandler;
import xyz.sculas.nacho.async.AsyncExplosions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldServer;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TicksPerSecondCommand extends Command {

	public TicksPerSecondCommand(String name) {
		super(name);
		this.description = "Gets the current ticks per second for the server";
		this.usageMessage = "/tps";
		this.setPermission("bukkit.command.tps");
	}

	@Override
	public boolean execute(CommandSender sender, String currentAlias, String[] args) {
		if (!testPermission(sender)) {
			return true;
		}
		try {

		// PaperSpigot start - Further improve tick handling
		MinecraftServer server = MinecraftServer.getServer();
		if (server == null) {
			sender.sendMessage(ChatColor.RED + "TPS is unavailable while the server is starting.");
			return true;
		}
		String[] tpsAvg = new String[] {
				format(server.tps5s.getAverage()), format(server.tps10s.getAverage()),
				format(server.tps1.getAverage()), format(server.tps5.getAverage()),
				format(server.tps15.getAverage())
		};
		
		// WindSpigot - more detailed tps cmd
		
		int entityCount = 0;
		
		if (server.worlds != null) for (WorldServer world : server.worlds) {
			if (world != null && world.entityList != null) entityCount = entityCount + world.entityList.size();
		}
		
		int tileEntityCount = 0;
		List<FastNetworkManager> trackingManagers = new ArrayList<FastNetworkManager>();
		
		if (server.worlds != null) for (WorldServer world : server.worlds) {
			if (world == null) continue;
			if (world.tileEntityList != null) tileEntityCount = tileEntityCount + world.tileEntityList.size();
			if (world.players == null) continue;
			for (net.minecraft.server.EntityHuman human : world.players) {
				if (human instanceof net.minecraft.server.EntityPlayer) {
					net.minecraft.server.PlayerConnection connection = ((net.minecraft.server.EntityPlayer) human).playerConnection;
					trackingManagers.add(trackingManager(connection));
				}
			}
		}
		long[] trackingPacketCounts = trackingPacketCounts(trackingManagers);
		long queuedTrackingPackets = trackingPacketCounts[0];
		long droppedTrackingPackets = trackingPacketCounts[1];
		
		sender.sendMessage(ChatColor.DARK_AQUA + "WindSpigot Performance:");
		sender.sendMessage(ChatColor.AQUA + "TPS from last 5s, 10s, 1m, 5m, 15m: "
				+ org.apache.commons.lang.StringUtils.join(tpsAvg, ", "));
		TickPerformanceMonitor monitor = server.performanceMonitor;
		TickPerformanceMonitor.TickStats tenSecondTicks = monitor == null ? null : monitor.tickStats(20 * 10);
		TickPerformanceMonitor.TickStats oneMinuteTicks = monitor == null ? null : monitor.tickStats(20 * 60);
		sender.sendMessage(ChatColor.AQUA + "Tick durations (min/med/p95/p99/max ms) from last 10s, 1m: "
				+ formatTickStats(tenSecondTicks) + "   " + formatTickStats(oneMinuteTicks));
		TickPerformanceMonitor.CpuStats tenSecondCpu = monitor == null ? null : monitor.cpuStats(TickPerformanceMonitor.cpuWindowSamples(10));
		TickPerformanceMonitor.CpuStats oneMinuteCpu = monitor == null ? null : monitor.cpuStats(TickPerformanceMonitor.cpuWindowSamples(60));
		TickPerformanceMonitor.CpuStats fifteenMinuteCpu = monitor == null ? null : monitor.cpuStats(TickPerformanceMonitor.cpuWindowSamples(15 * 60));
		sender.sendMessage(ChatColor.AQUA + "CPU usage from last 10s, 1m, 15m:");
		sender.sendMessage(ChatColor.GREEN + "  " + formatCpu(systemPercent(tenSecondCpu)) + ", "
				+ formatCpu(systemPercent(oneMinuteCpu)) + ", "
				+ formatCpu(systemPercent(fifteenMinuteCpu)) + " (system)");
		sender.sendMessage(ChatColor.GREEN + "  " + formatCpu(processPercent(tenSecondCpu)) + ", "
				+ formatCpu(processPercent(oneMinuteCpu)) + ", "
				+ formatCpu(processPercent(fifteenMinuteCpu)) + " (process)");
		sender.sendMessage(ChatColor.AQUA + "Current Memory Usage: " + ChatColor.GREEN
				+ ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)) + "/"
				+ (Runtime.getRuntime().totalMemory() / (1024 * 1024)) + " mb (Max: "
				+ (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " mb)");
		sender.sendMessage(ChatColor.AQUA + "Online Players: " + ChatColor.GREEN + Bukkit.getOnlinePlayers().size());
		sender.sendMessage(ChatColor.AQUA + "Entity Count: " + ChatColor.GREEN + entityCount);
		sender.sendMessage(ChatColor.AQUA + "Tile Entity Count: " + ChatColor.GREEN + tileEntityCount);
		sender.sendMessage(ChatColor.AQUA + "Tracking Queue: " + ChatColor.GREEN + queuedTrackingPackets
				+ " queued, " + droppedTrackingPackets + " dropped");
		BoundedThreadPoolExecutor.Metrics explosionMetrics = AsyncExplosions.snapshotMetrics();
		BoundedThreadPoolExecutor.Metrics pathMetrics = SearchHandler.getInstance() == null
				? null : SearchHandler.getInstance().snapshotMetrics();
		sender.sendMessage(ChatColor.AQUA + "Async queues: " + ChatColor.GREEN
				+ formatExecutor("explosion", explosionMetrics) + ChatColor.GRAY + ", "
				+ ChatColor.GREEN + formatExecutor("path", pathMetrics));
		boolean mobAi = server.worlds != null && !server.worlds.isEmpty() && server.worlds.get(0) != null
				&& server.worlds.get(0).nachoSpigotConfig != null && server.worlds.get(0).nachoSpigotConfig.enableMobAI;
		sender.sendMessage(ChatColor.AQUA + "Mob AI: " + ChatColor.GREEN + mobAi);
		sender.sendMessage(ChatColor.AQUA + "Milliseconds to Run Last Tick: " + ChatColor.GREEN + Math.round(server.getLastMspt() * 100.0) / 100.0);
		
		return true;
		} catch (Throwable failure) {
			sender.sendMessage(ChatColor.RED + "TPS diagnostics unavailable: "
					+ (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
			Logger.getLogger("WindSpigot").log(Level.WARNING, "/tps diagnostics failed", failure);
			return true;
		}
	}

	static long[] trackingPacketCounts(Iterable<FastNetworkManager> managers) {
		long queued = 0L;
		long dropped = 0L;
		if (managers == null) return new long[] {queued, dropped};
		for (FastNetworkManager manager : managers) {
			if (manager == null) continue;
			try {
				queued += manager.queuedPacketCount();
				dropped += manager.droppedPacketCount();
			} catch (RuntimeException ignored) {
				// A connection can be torn down while diagnostics are collected.
			}
		}
		return new long[] {queued, dropped};
	}

	static FastNetworkManager trackingManager(net.minecraft.server.PlayerConnection connection) {
		if (connection == null) return null;
		net.minecraft.server.NetworkManager network = connection.networkManager;
		return network == null ? null : network.fastNetworkManager;
	}

	private static String format(double tps) // PaperSpigot - made static
	{
		return ((tps > 18.0) ? ChatColor.GREEN : (tps > 16.0) ? ChatColor.YELLOW : ChatColor.RED).toString()
				+ ((tps > 21.0) ? "*" : "") + Math.min(Math.round(tps * 100.0) / 100.0, 20.0); // Paper - only print * at 21, we commonly peak to 20.02 as the tick sleep is not accurate enough, stop the noise
	}

	private static String formatTickStats(TickPerformanceMonitor.TickStats stats) {
		if (stats == null || stats.getSampleCount() == 0) {
			return "N/A/N/A/N/A/N/A/N/A";
		}
		return formatMillis(stats.getMinNanos()) + "/" + formatMillis(stats.getMedianNanos()) + "/"
				+ formatMillis(stats.getP95Nanos()) + "/" + formatMillis(stats.getP99Nanos()) + "/"
				+ formatMillis(stats.getMaxNanos());
	}

	private static String formatMillis(double nanos) {
		return String.valueOf(Math.round(nanos / 100000.0D) / 10.0D);
	}

	private static String formatCpu(double percent) {
		return Double.isNaN(percent) ? "N/A" : Math.round(percent) + "%";
	}

	private static double systemPercent(TickPerformanceMonitor.CpuStats stats) {
		return stats == null ? Double.NaN : stats.getSystemPercent();
	}

	private static double processPercent(TickPerformanceMonitor.CpuStats stats) {
		return stats == null ? Double.NaN : stats.getProcessPercent();
	}

	private static String formatExecutor(String name, BoundedThreadPoolExecutor.Metrics metrics) {
		if (metrics == null) return name + "=off";
		return name + "=" + metrics.getQueuedTasks() + "/" + metrics.getQueueCapacity()
				+ " active=" + metrics.getActiveWorkers()
				+ " rejected=" + metrics.getRejectedTasks();
	}
}
