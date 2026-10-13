package xyz.sculas.nacho.async;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.windpvp.windspigot.async.BoundedThreadPoolExecutor;
import com.windpvp.windspigot.config.WindSpigotConfig;

public class AsyncExplosions {
	public static BoundedThreadPoolExecutor EXECUTOR;

	public static void initExecutor(int size) {
		EXECUTOR = new BoundedThreadPoolExecutor(size, WindSpigotConfig.explosionQueueCapacity,
				new ThreadFactoryBuilder().setNameFormat("WindSpigot Explosion Thread %d").setDaemon(true).build());
	}

	public static void stopExecutor() {
		if (EXECUTOR != null) {
			EXECUTOR.shutdown();
			try {
				if (!EXECUTOR.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
					EXECUTOR.shutdownNow();
					if (!EXECUTOR.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
						throw new IllegalStateException("Explosion workers did not terminate during shutdown");
					}
				}
			} catch (InterruptedException interrupted) {
				EXECUTOR.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

	public static BoundedThreadPoolExecutor.Metrics snapshotMetrics() {
		BoundedThreadPoolExecutor executor = EXECUTOR;
		return executor == null ? null : executor.snapshot();
	}
}
