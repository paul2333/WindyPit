
package com.windpvp.windspigot.scheduler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-region bot budget (W3-NPC-BOT). Limits the number of concurrently active bots per region
 * to prevent AI cost from overwhelming the owner thread. acquire() returns false when the budget
 * is exhausted; release() frees a slot when a bot is destroyed.
 */
public final class BotBudget {

    private final int maxBotsPerRegion;
    private final Map<String, AtomicInteger> activeBots = new ConcurrentHashMap<>();

    public BotBudget(int maxBotsPerRegion) {
        if (maxBotsPerRegion <= 0) throw new IllegalArgumentException("maxBotsPerRegion must be positive");
        this.maxBotsPerRegion = maxBotsPerRegion;
    }

    /** Returns true if a bot slot was acquired for this region. */
    public boolean acquire(String regionId) {
        AtomicInteger counter = activeBots.computeIfAbsent(regionId, k -> new AtomicInteger());
        for (;;) {
            int current = counter.get();
            if (current >= maxBotsPerRegion) return false;
            if (counter.compareAndSet(current, current + 1)) return true;
        }
    }

    /** Releases a bot slot. Idempotent: does not go below zero. */
    public void release(String regionId) {
        AtomicInteger counter = activeBots.get(regionId);
        if (counter != null) {
            counter.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    public int activeBots(String regionId) {
        AtomicInteger counter = activeBots.get(regionId);
        return counter == null ? 0 : counter.get();
    }

    public int maxBotsPerRegion() {
        return maxBotsPerRegion;
    }

    public boolean hasCapacity(String regionId) {
        return activeBots(regionId) < maxBotsPerRegion;
    }
}

