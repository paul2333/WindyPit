
package com.windpvp.windspigot.scheduler;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bot AI decision dispatcher (W3-NPC-BOT). Routes pure bot decisions (pathfinding, target
 * selection) through the ComputePool as async jobs and delivers results to the bot's entity
 * owner thread. Decisions are tagged with entity ID and sequence for staleness detection.
 */
public final class BotDecisionDispatcher {

    private final ComputePool pool;
    private final AtomicLong sequences = new AtomicLong();

    public BotDecisionDispatcher(ComputePool pool) {
        this.pool = pool;
    }

    /**
     * Submits a bot decision for async computation. The decision work must be pure (no entity
     * mutation); the result is delivered to the bot's owner via onOwner.
     */
    public <T> void submitDecision(OwnedRegion region, int botEntityId, Callable<T> decision,
                                   java.util.function.Consumer<T> onOwner) {
        long seq = sequences.incrementAndGet();
        pool.submit(new ComputePool.ComputeJob<T>(region, 1L, botEntityId, seq, decision, onOwner));
    }
}

