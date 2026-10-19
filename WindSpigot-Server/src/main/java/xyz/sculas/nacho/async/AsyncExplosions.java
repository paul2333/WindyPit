package xyz.sculas.nacho.async;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.windpvp.windspigot.async.BoundedThreadPoolExecutor;
import com.windpvp.windspigot.config.WindSpigotConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Explosion density ray tracing on the explosion worker pool.
 *
 * <p>The pool existed but had no callers: {@code settings.async.explosions.enable} created
 * threads that never received a job. This gives the pool its first real workload.
 *
 * <p>Safety model: the server thread fans the rays out and then <b>blocks</b> until every future
 * completes. While it blocks it performs no block writes, so the only hazard is a concurrent
 * write from another thread; {@code Explosion} guards against that by re-checking the chunk
 * block generations after the fan-out and falling back to the synchronous trace when anything
 * moved. Chunk loading never happens on the workers because the density path reads blocks with
 * {@code getTypeIfLoaded}, which returns null for unloaded chunks.
 */
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

	/** True when the explosion pool is running and the caller is not one of its workers. */
	public static boolean canFanOut() {
		final BoundedThreadPoolExecutor executor = EXECUTOR;
		if (executor == null || executor.isShutdown()) {
			return false;
		}
		final Thread current = Thread.currentThread();
		// Never fan out from a worker: it would deadlock the pool waiting on itself.
		return !executor.isOnWorker(current);
	}

	/**
	 * Runs the ray-trace callables on the explosion workers and blocks for the results.
	 *
	 * <p>Every callable must be a pure block read. If any submission is rejected, a worker fails
	 * or the deadline passes, the surviving results are abandoned and the caller falls back to a
	 * synchronous trace, so a partially computed density can never reach the cache.
	 *
	 * @param timeoutMillis how long the server thread may block; {@code <= 0} means no wait
	 * @return the per-callable results in submission order, or null when the fan-out must abort
	 */
	public static <T> List<T> awaitAll(List<Callable<T>> jobs, long timeoutMillis) {
		final BoundedThreadPoolExecutor executor = EXECUTOR;
		if (executor == null || executor.isShutdown() || jobs.isEmpty()) {
			return null;
		}
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
		final List<Future<T>> futures = new ArrayList<Future<T>>(jobs.size());
		try {
			for (Callable<T> job : jobs) {
				futures.add(executor.submit(job));
			}
		} catch (RuntimeException rejected) {
			// Queue saturated: cancel whatever was accepted and let the caller go synchronous.
			for (Future<T> future : futures) {
				future.cancel(false);
			}
			return null;
		}
		final List<T> results = new ArrayList<T>(jobs.size());
		try {
			for (Future<T> future : futures) {
				final long remainingNanos = deadline - System.nanoTime();
				if (remainingNanos <= 0L) {
					for (Future<T> pending : futures) {
						pending.cancel(false);
					}
					return null;
				}
				results.add(future.get(remainingNanos, TimeUnit.NANOSECONDS));
			}
			return results;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return null;
		} catch (java.util.concurrent.TimeoutException | ExecutionException | RuntimeException failure) {
			for (Future<T> pending : futures) {
				pending.cancel(false);
			}
			return null;
		}
	}

	/** Caps how long the server thread may block on one explosion fan-out. */
	public static long awaitTimeoutMillis() {
		return 50L;
	}
}
