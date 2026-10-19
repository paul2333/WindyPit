package com.windpvp.windspigot.async.pathsearch;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.windpvp.windspigot.async.AsyncExecutionPolicy;
import com.windpvp.windspigot.async.BoundedThreadPoolExecutor;
import com.windpvp.windspigot.async.pathsearch.cache.SearchCacheEntryEntity;
import com.windpvp.windspigot.async.pathsearch.cache.SearchCacheEntryPosition;
import com.windpvp.windspigot.config.WindSpigotConfig;

import net.minecraft.server.ChunkCache;
import net.minecraft.server.Entity;
import net.minecraft.server.MathHelper;
import net.minecraft.server.PathEntity;

public class SearchHandler {

	private static SearchHandler INSTANCE;
	private final BoundedThreadPoolExecutor executor;

	public SearchHandler() {
		if (AsyncExecutionPolicy.isEnabled(WindSpigotConfig.asyncPathSearches, true)) {
			executor = new BoundedThreadPoolExecutor(
					WindSpigotConfig.pathSearchThreads, WindSpigotConfig.pathSearchQueueCapacity,
					new ThreadFactoryBuilder().setNameFormat("WindSpigot Entity Path Search Thread %d").setDaemon(true).build());
		} else {
			executor = null;
		}
		INSTANCE = this;
	}

	public void issueSearch(Entity targetEntity, AsyncNavigation navigation) {
		if (!AsyncExecutionPolicy.isEnabled(WindSpigotConfig.asyncPathSearches, true)) {
			return;
		}
		
		final ChunkCache chunkCache = navigation.createChunkCache(true);
		
		if (chunkCache == null) {
			return;
		}

		navigation.isSearching.set(true);
		
		final int finalX = MathHelper.floor(targetEntity.locX);
		final int finalY = MathHelper.floor(targetEntity.locY) + 1;
		final int finalZ = MathHelper.floor(targetEntity.locZ);
		// WindyPit start - capture the block generations on the primary thread
		final int[] generation = chunkCache.captureGeneration();
		// WindyPit end
		
		Runnable task = () -> {
			
			try {
				PathEntity path = navigation.doPathSearch(chunkCache, finalX, finalY, finalZ);
				// WindyPit start - discard results computed against a world that moved
				if (!chunkCache.matchesGeneration(generation)) {
					return;
				}
				// WindyPit end
				SearchCacheEntryEntity cache = new SearchCacheEntryEntity(targetEntity, navigation.getEntity(), path);
				navigation.addEntry(cache);
			} finally {
				navigation.isSearching.set(false);
			}

		};
		if (!executor.tryExecute(task)) {
			navigation.isSearching.set(false);
		}
	}

	public static SearchHandler getInstance() {
		return INSTANCE;
	}

	public void issueSearch(int x, int y, int z, AsyncNavigation navigation) {
		if (!AsyncExecutionPolicy.isEnabled(WindSpigotConfig.asyncPathSearches, true)) {
			return;
		}

		final ChunkCache chunkCache = navigation.createChunkCache(false);
		
		if (chunkCache == null) {
			return;
		}
		
		navigation.isSearching.set(true);
		
		// WindyPit start - capture the block generations on the primary thread
		final int[] generation = chunkCache.captureGeneration();
		// WindyPit end

		Runnable task = () -> {
			
			try {
				PathEntity path = navigation.doPathSearch(chunkCache, x, y, z);
				// WindyPit start - discard results computed against a world that moved
				if (!chunkCache.matchesGeneration(generation)) {
					return;
				}
				// WindyPit end
				SearchCacheEntryPosition cache = new SearchCacheEntryPosition(x, y, z, navigation.getEntity(), path);
				navigation.addEntry(cache);
			} finally {
				navigation.isSearching.set(false);
			}

		};
		if (!executor.tryExecute(task)) {
			navigation.isSearching.set(false);
		}
	}

	public void shutdown() {
		if (executor == null) {
			return;
		}
		executor.shutdown();
		try {
			if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
				executor.shutdownNow();
				if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
					throw new IllegalStateException("Path-search workers did not terminate during shutdown");
				}
			}
		} catch (InterruptedException interrupted) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	public BoundedThreadPoolExecutor.Metrics snapshotMetrics() {
		return executor == null ? null : executor.snapshot();
	}

}
