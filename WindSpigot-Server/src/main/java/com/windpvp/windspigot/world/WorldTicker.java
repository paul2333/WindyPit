
package com.windpvp.windspigot.world;

import com.windpvp.windspigot.scheduler.WindyPitTickIntegration;
import com.windpvp.windspigot.scheduler.WorldRegionRuntime;
import net.minecraft.server.CrashReport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReportedException;
import net.minecraft.server.WorldServer;

public class WorldTicker implements Runnable, WorldTrackerTick.Target {

	public final WorldServer worldserver;
	
	public WorldTicker(WorldServer worldServer) {
		this.worldserver = worldServer;
	}

	// WindyPit - per-world tick duration ring for /windypit-tps. Written only on the primary
	// thread, read only by the command thread; 600 samples (30 s at 20 TPS) with no per-tick
	// allocation. Percentiles are computed on demand, never in the tick path.
	private final long[] tickDurationsNanos = new long[600];
	private int tickDurationCount;
	private int tickDurationIndex;
	private volatile long lastTickNanos;

	private void recordTickDuration(long nanos) {
		lastTickNanos = nanos;
		tickDurationsNanos[tickDurationIndex] = nanos;
		tickDurationIndex = (tickDurationIndex + 1) % tickDurationsNanos.length;
		if (tickDurationCount < tickDurationsNanos.length) {
			tickDurationCount++;
		}
	}

	public double lastTickMillis() {
		return lastTickNanos / 1_000_000.0D;
	}

	/** p-th percentile (0..100) of the recent tick durations, or -1 when no samples exist. */
	public double tickPercentileMillis(double percentile) {
		final int count = tickDurationCount;
		if (count == 0) {
			return -1.0D;
		}
		final long[] copy = new long[count];
		System.arraycopy(tickDurationsNanos, 0, copy, 0, count);
		java.util.Arrays.sort(copy);
		int index = (int) Math.ceil(percentile / 100.0D * count) - 1;
		if (index < 0) {
			index = 0;
		}
		if (index >= count) {
			index = count - 1;
		}
		return copy[index] / 1_000_000.0D;
	}

	// This is mostly copied code from world ticking
	@Override
	public void run() {
		final long startedNanos = System.nanoTime();
		try {
		// this.methodProfiler.a(worldserver.getWorldData().getName());
		// this.methodProfiler.a("tick");
		CrashReport crashreport;

		try {
			worldserver.timings.doTick.startTiming(); // Spigot
			worldserver.doTick();
			worldserver.timings.doTick.stopTiming(); // Spigot
		} catch (Throwable throwable) {
			// Spigot Start
			try {
				crashreport = CrashReport.a(throwable, "Exception ticking world");
			} catch (Throwable t) {
				throw new RuntimeException("Error generating crash report", t);
			}
			// Spigot End
			worldserver.a(crashreport);
			throw new ReportedException(crashreport);
		}

		try {
			worldserver.timings.tickEntities.startTiming(); // Spigot
			worldserver.tickEntities();
			worldserver.timings.tickEntities.stopTiming(); // Spigot
		} catch (Throwable throwable1) {
			// Spigot Start
			try {
				crashreport = CrashReport.a(throwable1, "Exception ticking world entities");
			} catch (Throwable t) {
				throw new RuntimeException("Error generating crash report", t);
			}
			// Spigot End
			worldserver.a(crashreport);
			throw new ReportedException(crashreport);
		}

		// WindSpigot - tracker step; the timing window is always closed (see WorldTrackerTick)
		// WindyPit start - per-world owner domains (W1-REGION-REAL)
		// The primary thread applies the previous tick's immutable visibility plan before the
		// tracker step, then builds a fresh immutable snapshot and hands it to this world's owner
		// thread. NMS objects never cross the owner boundary; only immutable data does.
		final String worldName = worldserver.getWorld().getName();
		final WindyPitTickIntegration integration = MinecraftServer.getServer() == null
				? null : MinecraftServer.getServer().getWindyTick();
		final WorldRegionRuntime worldRegions = integration == null ? null : integration.worldRegions();
		final boolean ownerPlans = worldRegions != null && worldRegions.isRegistered(worldName);
		if (ownerPlans) {
			worldRegions.applyPending(worldName);
		}
		WorldTrackerTick.run(this);
		if (ownerPlans) {
			// 【2026-10-23 可用性修复】把"计划流是否还活着"回报给 tracker。
			//
			// plan-driven 模式下 tracker 跳过 add/remove、完全依赖计划；
			// 一旦计划流断掉（完成队列满被丢弃、owner 线程挂起），玩家就再也看不到实体，
			// 而原实现没有任何自动恢复。EntityTracker 据这个时间戳判断停滞并退回原版路径。
			//
			// 判据选在"本 tick 成功提交了新快照"而不是"应用了计划"：
			// 计划是异步产出的，健康状态下也会有"本 tick 没有新计划可应用"的正常空窗，
			// 用 applied > 0 会误判为停滞。真正的中断是**连提交都失败**（队列满/已关闭）
			// 且持续超过阈值 —— 那才是必须退回原版的时刻。
			if (worldRegions.submitWorld(worldserver)) {
				worldserver.getTracker().markPlanApplied();
			}
		}
		// WindyPit end
		// this.methodProfiler.b();
		// this.methodProfiler.b();
		worldserver.explosionDensityCache.clear(); // Paper - Optimize explosions
		} finally {
			recordTickDuration(System.nanoTime() - startedNanos);
		}
	}

	// WorldTrackerTick.Target - one invocation per world tick, on the server thread
	@Override
	public int worldPlayerCount() {
		return worldserver.players.size();
	}

	@Override
	public void startTrackerTiming() {
		worldserver.timings.tracker.startTiming(); // Spigot
	}

	@Override
	public void updateTracker() {
		worldserver.getTracker().updatePlayers();
	}

	@Override
	public void stopTrackerTiming() {
		worldserver.timings.tracker.stopTiming(); // Spigot
	}
}

