package org.spigotmc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import com.windpvp.windspigot.performance.TickPerformanceMonitor;
import com.windpvp.windspigot.async.BoundedThreadPoolExecutor;
import com.windpvp.windspigot.async.pathsearch.SearchHandler;
import xyz.sculas.nacho.async.AsyncExplosions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldServer;

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

		// PaperSpigot start - Further improve tick handling
		MinecraftServer server = MinecraftServer.getServer();
		String[] tpsAvg = new String[] {
				format(server.tps5s.getAverage()), format(server.tps10s.getAverage()),
				format(server.tps1.getAverage()), format(server.tps5.getAverage()),
				format(server.tps15.getAverage())
		};
		
		// WindSpigot - more detailed tps cmd
		
		int entityCount = 0;
		
		for (WorldServer world : server.worlds) {
			entityCount = entityCount + world.entityList.size();
		}
		
		int tileEntityCount = 0;
		long queuedTrackingPackets = 0L;
		long droppedTrackingPackets = 0L;
		
		for (WorldServer world : server.worlds) {
			tileEntityCount = tileEntityCount + world.tileEntityList.size();
			for (net.minecraft.server.EntityHuman human : world.players) {
				if (human instanceof net.minecraft.server.EntityPlayer) {
					net.minecraft.server.NetworkManager network = ((net.minecraft.server.EntityPlayer) human)
							.playerConnection.networkManager;
					queuedTrackingPackets += network.fastNetworkManager.queuedPacketCount();
					droppedTrackingPackets += network.fastNetworkManager.droppedPacketCount();
				}
			}
		}
		
		sender.sendMessage(ChatColor.DARK_AQUA + "WindSpigot Performance:");
		sender.sendMessage(ChatColor.AQUA + "TPS from last 5s, 10s, 1m, 5m, 15m: "
				+ org.apache.commons.lang.StringUtils.join(tpsAvg, ", "));
		TickPerformanceMonitor.TickStats tenSecondTicks = server.performanceMonitor.tickStats(20 * 10);
		TickPerformanceMonitor.TickStats oneMinuteTicks = server.performanceMonitor.tickStats(20 * 60);
		sender.sendMessage(ChatColor.AQUA + "Tick durations (min/med/p95/p99/max ms) from last 10s, 1m: "
				+ formatTickStats(tenSecondTicks) + "   " + formatTickStats(oneMinuteTicks));
		TickPerformanceMonitor.CpuStats tenSecondCpu = server.performanceMonitor
				.cpuStats(TickPerformanceMonitor.cpuWindowSamples(10));
		TickPerformanceMonitor.CpuStats oneMinuteCpu = server.performanceMonitor
				.cpuStats(TickPerformanceMonitor.cpuWindowSamples(60));
		TickPerformanceMonitor.CpuStats fifteenMinuteCpu = server.performanceMonitor
				.cpuStats(TickPerformanceMonitor.cpuWindowSamples(15 * 60));
		sender.sendMessage(ChatColor.AQUA + "CPU usage from last 10s, 1m, 15m:");
		sender.sendMessage(ChatColor.GREEN + "  " + formatCpu(tenSecondCpu.getSystemPercent()) + ", "
				+ formatCpu(oneMinuteCpu.getSystemPercent()) + ", "
				+ formatCpu(fifteenMinuteCpu.getSystemPercent()) + " (system)");
		sender.sendMessage(ChatColor.GREEN + "  " + formatCpu(tenSecondCpu.getProcessPercent()) + ", "
				+ formatCpu(oneMinuteCpu.getProcessPercent()) + ", "
				+ formatCpu(fifteenMinuteCpu.getProcessPercent()) + " (process)");
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
		boolean mobAi = !server.worlds.isEmpty() && server.worlds.get(0).nachoSpigotConfig.enableMobAI;
		sender.sendMessage(ChatColor.AQUA + "Mob AI: " + ChatColor.GREEN + mobAi);
		sender.sendMessage(ChatColor.AQUA + "Milliseconds to Run Last Tick: " + ChatColor.GREEN + Math.round(server.getLastMspt() * 100.0) / 100.0);
		
		return true;
	}

	private static String format(double tps) // PaperSpigot - made static
	{
		return ((tps > 18.0) ? ChatColor.GREEN : (tps > 16.0) ? ChatColor.YELLOW : ChatColor.RED).toString()
				+ ((tps > 21.0) ? "*" : "") + Math.min(Math.round(tps * 100.0) / 100.0, 20.0); // Paper - only print * at 21, we commonly peak to 20.02 as the tick sleep is not accurate enough, stop the noise
	}

	private static String formatTickStats(TickPerformanceMonitor.TickStats stats) {
		if (stats.getSampleCount() == 0) {
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

	private static String formatExecutor(String name, BoundedThreadPoolExecutor.Metrics metrics) {
		if (metrics == null) return name + "=off";
		return name + "=" + metrics.getQueuedTasks() + "/" + metrics.getQueueCapacity()
				+ " active=" + metrics.getActiveWorkers()
				+ " rejected=" + metrics.getRejectedTasks();
	}
}
