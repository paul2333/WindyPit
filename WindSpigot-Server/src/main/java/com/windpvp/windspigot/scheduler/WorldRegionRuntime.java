package com.windpvp.windspigot.scheduler;

import net.minecraft.server.Entity;
import net.minecraft.server.EntityArrow;
import net.minecraft.server.EntityFishingHook;
import net.minecraft.server.EntityHuman;
import net.minecraft.server.EntityPotion;
import net.minecraft.server.EntityTNTPrimed;
import net.minecraft.server.EntityTrackerEntry;
import net.minecraft.server.WorldServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Production per-world owner domains for WindyPit (W1-REGION-REAL).
 *
 * <p>Every registered world gets one owner domain (a dedicated fixed thread, or a shared domain
 * when the configured region-thread budget is smaller than the world count). The primary thread
 * only ever:</p>
 *
 * <ul>
 *   <li>builds an immutable {@link TrackerSnapshot} of entity/viewer positions after the world
 *       tick (pure data, no NMS references leave the thread), and</li>
 *   <li>applies the resulting visibility plan on the primary thread through
 *       {@link PlanApplier} (NMS tracker mutation is never executed on a worker).</li>
 * </ul>
 *
 * <p>Owner threads run the real {@link TrackerVisibilityPlanner} against the immutable snapshots
 * and deliver one plan per world/tick through a bounded per-world queue. A plan is dropped, never
 * silently lost, when the queue is full or the world generation is stale; the next tick forces a
 * full resync so a dropped removal cannot leak forever.</p>
 *
 * <p>The shared ThePit arena is intentionally NOT part of this runtime: it stays a single pumped
 * owner (see {@link WindyPitTickIntegration#arena()}).</p>
 */
public final class WorldRegionRuntime {

    /**
     * Optional observability hook invoked on the owner thread after a plan is computed. Used by
     * diagnostics and by tests to prove that independent domains really run concurrently.
     */
    public interface PlanningObserver {
        void onPlanComputed(String worldName, String ownerThreadName, long startedNanos, long durationNanos);
    }

    /** Pure planning function; the production implementation delegates to the real planner. */
    public interface PlanFunction {
        TrackerVisibilityPlanner.Plan plan(TrackerSnapshot current, TrackerSnapshot previous);
    }

    /** Applies one visibility plan to a live world. Must run on the primary/owner thread. */
    public interface PlanApplier {
        /**
         * @return true when the plan was applied, false when the world no longer exists or the
         *         tracker rejected the plan (counted as a failure and resynced next tick).
         */
        boolean apply(String worldName, TrackerVisibilityPlanner.Plan plan);
    }

    /** Immutable result of one owner-domain planning pass. */
    public static final class Plan {
        private final String worldName;
        private final long generation;
        private final TrackerVisibilityPlanner.Plan visibility;

        Plan(String worldName, long generation, TrackerVisibilityPlanner.Plan visibility) {
            this.worldName = worldName;
            this.generation = generation;
            this.visibility = visibility;
        }

        public String worldName() {
            return worldName;
        }

        public long generation() {
            return generation;
        }

        public TrackerVisibilityPlanner.Plan visibility() {
            return visibility;
        }
    }

    public static final class WorldStats {
        private final String worldName;
        private final String regionId;
        private final long submitted;
        private final long planned;
        private final long applied;
        private final long dropped;
        private final long rejected;
        private final long failed;
        private final int queueDepth;
        private final long appliedGeneration;

        WorldStats(String worldName, String regionId, long submitted, long planned, long applied,
                   long dropped, long rejected, long failed, int queueDepth, long appliedGeneration) {
            this.worldName = worldName;
            this.regionId = regionId;
            this.submitted = submitted;
            this.planned = planned;
            this.applied = applied;
            this.dropped = dropped;
            this.rejected = rejected;
            this.failed = failed;
            this.queueDepth = queueDepth;
            this.appliedGeneration = appliedGeneration;
        }

        public String worldName() { return worldName; }
        public String regionId() { return regionId; }
        public long submitted() { return submitted; }
        public long planned() { return planned; }
        public long applied() { return applied; }
        public long dropped() { return dropped; }
        public long rejected() { return rejected; }
        public long failed() { return failed; }
        public int queueDepth() { return queueDepth; }
        public long appliedGeneration() { return appliedGeneration; }
    }

    private static final class Domain {
        final String id;
        final OwnedRegion region;
        final List<WorldState> worlds = new ArrayList<WorldState>();

        Domain(String id, int queueCapacity) {
            this.id = id;
            this.region = OwnedRegion.dedicated(id, RegionKind.WORLD, queueCapacity);
        }
    }

    private static final class WorldState {
        final String worldName;
        final Domain domain;
        final ArrayBlockingQueue<Plan> completed;
        final AtomicLong generation = new AtomicLong();
        final LongAdder submitted = new LongAdder();
        final LongAdder planned = new LongAdder();
        final LongAdder applied = new LongAdder();
        final LongAdder dropped = new LongAdder();
        final LongAdder rejected = new LongAdder();
        final LongAdder failed = new LongAdder();
        volatile TrackerSnapshot previous;
        volatile boolean active = true;
        volatile long appliedGeneration;

        WorldState(String worldName, Domain domain, int queueCapacity) {
            this.worldName = worldName;
            this.domain = domain;
            this.completed = new ArrayBlockingQueue<Plan>(queueCapacity);
        }
    }

    private final RegionScheduler scheduler;
    private final PlanFunction planFunction;
    private final PlanApplier applier;
    private final int maxDomains;
    private final int planQueueCapacity;
    private volatile PlanningObserver planningObserver;
    private final Map<String, WorldState> byWorld = new ConcurrentHashMap<String, WorldState>();
    private final List<Domain> domains = new ArrayList<Domain>();
    private final AtomicLong droppedTotal = new AtomicLong();
    private final AtomicLong rejectedTotal = new AtomicLong();
    private final AtomicLong failedTotal = new AtomicLong();
    private final AtomicLong appliedTotal = new AtomicLong();
    private volatile boolean closed;
    private int nextDomain;

    public WorldRegionRuntime(RegionScheduler scheduler, final TrackerVisibilityPlanner planner,
                              PlanApplier applier, int maxDomains, int planQueueCapacity) {
        this(scheduler, planner == null ? null : new PlanFunction() {
            @Override
            public TrackerVisibilityPlanner.Plan plan(TrackerSnapshot current, TrackerSnapshot previous) {
                return planner.plan(current, previous, null);
            }
        }, applier, maxDomains, planQueueCapacity);
    }

    public WorldRegionRuntime(RegionScheduler scheduler, PlanFunction planFunction,
                              PlanApplier applier, int maxDomains, int planQueueCapacity) {
        if (scheduler == null || planFunction == null || applier == null) {
            throw new IllegalArgumentException("scheduler, plan function and applier are required");
        }
        this.scheduler = scheduler;
        this.planFunction = planFunction;
        this.applier = applier;
        this.maxDomains = Math.max(1, maxDomains);
        this.planQueueCapacity = Math.max(1, planQueueCapacity);
    }

    /**
     * Registers one world; called on the primary thread. Existing worlds are left untouched.
     *
     * @return true when a new world owner binding was created, false when the world already
     *         existed (or the runtime is closed)
     */
    public synchronized boolean registerWorld(String worldName) {
        if (closed || worldName == null) {
            return false;
        }
        final WorldState existing = byWorld.get(worldName);
        if (existing != null && existing.active) {
            return false;
        }
        final Domain domain;
        if (existing != null) {
            // Re-register a world that was retired by retainWorlds: keep its owner domain so the
            // fixed thread identity is stable across a world reload.
            domain = existing.domain;
            domain.worlds.remove(existing);
        } else if (domains.size() < maxDomains) {
            domain = new Domain("world-owner-" + domains.size(), 64);
            domains.add(domain);
            scheduler.register(domain.region);
        } else {
            domain = domains.get(nextDomain++ % domains.size());
        }
        final WorldState state = new WorldState(worldName, domain, planQueueCapacity);
        domain.worlds.add(state);
        byWorld.put(worldName, state);
        return true;
    }

    /**
     * Marks worlds that no longer exist as inactive and stops their planning.
     *
     * @return the world names that were retired by this call, so the caller can invalidate the
     *         matching owner generations in {@link WorldOwnerRegistry}
     */
    public synchronized List<String> retainWorlds(java.util.Collection<String> liveWorldNames) {
        final List<String> retired = new ArrayList<String>();
        for (Map.Entry<String, WorldState> e : byWorld.entrySet()) {
            if (!liveWorldNames.contains(e.getKey()) && e.getValue().active) {
                e.getValue().active = false;
                e.getValue().completed.clear();
                retired.add(e.getKey());
            }
        }
        return retired;
    }

    public void setPlanningObserver(PlanningObserver planningObserver) {
        this.planningObserver = planningObserver;
    }

    public boolean isRegistered(String worldName) {
        final WorldState s = byWorld.get(worldName);
        return s != null && s.active && !closed;
    }

    /**
     * Submits one planning job for the world. Must be called on the primary thread with an
     * immutable snapshot built after the world tick.
     */
    public void submitSnapshot(String worldName, final TrackerSnapshot snapshot) {
        if (closed || snapshot == null) {
            return;
        }
        final WorldState state = byWorld.get(worldName);
        if (state == null || !state.active) {
            return;
        }
        final TrackerSnapshot previous = state.previous;
        final long generation = snapshot.generation();
        final TrackerSnapshot current = snapshot;
        try {
            scheduler.schedule(state.domain.region, new Runnable() {
                @Override
                public void run() {
                    final long startedNanos = System.nanoTime();
                    try {
                        final TrackerVisibilityPlanner.Plan plan = planFunction.plan(current, previous);
                        state.planned.increment();
                        final PlanningObserver observer = planningObserver;
                        if (observer != null) {
                            observer.onPlanComputed(worldName, Thread.currentThread().getName(),
                                    startedNanos, System.nanoTime() - startedNanos);
                        }
                        final Plan result = new Plan(worldName, generation, plan);
                        if (!state.completed.offer(result)) {
                            // Bounded queue full: drop visibly and force a full resync next tick.
                            state.dropped.increment();
                            droppedTotal.incrementAndGet();
                            state.previous = null;
                        }
                    } catch (Throwable t) {
                        state.failed.increment();
                        failedTotal.incrementAndGet();
                        state.previous = null;
                    }
                }
            });
            state.submitted.increment();
            // Advance only after a successful enqueue; a lost plan resets to a full resync.
            state.previous = current;
        } catch (RejectedTaskException e) {
            state.rejected.increment();
            rejectedTotal.incrementAndGet();
            state.previous = null;
        }
    }

    /**
     * Builds an immutable snapshot of the live world and submits it to the world's owner domain.
     * Must be called on the primary thread after the world tick and only when the world has
     * players (a world without viewers produces no useful visibility work).
     */
    public void submitWorld(WorldServer world) {
        if (closed || world == null) {
            return;
        }
        final String worldName = world.getWorld().getName();
        final WorldState state = byWorld.get(worldName);
        if (state == null || !state.active) {
            return;
        }
        if (world.players.isEmpty()) {
            // No viewers: stop planning and force a full visibility resync when a player joins,
            // so a stale snapshot can never drive add/remove decisions after an empty period.
            state.previous = null;
            return;
        }
        final long generation = state.generation.incrementAndGet();
        submitSnapshot(worldName, buildSnapshot(world, worldName, generation));
    }

    private static TrackerSnapshot buildSnapshot(WorldServer world, String worldName, long generation) {
        final List<TrackerSnapshot.EntityView> entities =
                new ArrayList<TrackerSnapshot.EntityView>(world.entityList.size());
        for (Entity entity : world.entityList) {
            if (entity == null) {
                continue;
            }
            final EntityTrackerEntry entry = world.getTracker().trackedEntities.get(entity.getId());
            final int range = entry == null ? 0 : entry.getRange();
            final boolean lowLatency = entity instanceof EntityTNTPrimed
                    || entity instanceof EntityArrow
                    || entity instanceof EntityFishingHook
                    || entity instanceof EntityPotion;
            entities.add(new TrackerSnapshot.EntityView(entity.getId(), 0,
                    entity.locX, entity.locY, entity.locZ, entity.yaw, entity.pitch, entity.dead,
                    range, lowLatency));
        }
        final List<TrackerSnapshot.ViewerView> viewers =
                new ArrayList<TrackerSnapshot.ViewerView>(world.players.size());
        final int viewDistanceBlocks = Math.max(16, world.spigotConfig.viewDistance << 4);
        for (EntityHuman human : world.players) {
            viewers.add(new TrackerSnapshot.ViewerView(human.getId(),
                    human.locX, human.locY, human.locZ, viewDistanceBlocks));
        }
        return new TrackerSnapshot(generation, "world-" + worldName, entities, viewers);
    }

    /**
     * Applies every completed plan for the world on the calling (primary) thread. At most one plan
     * per tick is expected; stale generations are dropped and counted.
     */
    public int applyPending(String worldName) {
        if (closed) {
            return 0;
        }
        final WorldState state = byWorld.get(worldName);
        if (state == null || !state.active) {
            return 0;
        }
        int applied = 0;
        Plan plan;
        while ((plan = state.completed.poll()) != null) {
            if (plan.generation() <= state.appliedGeneration) {
                state.dropped.increment();
                droppedTotal.incrementAndGet();
                continue;
            }
            boolean ok;
            try {
                ok = applier.apply(worldName, plan.visibility());
            } catch (Throwable t) {
                ok = false;
                state.failed.increment();
                failedTotal.incrementAndGet();
            }
            if (ok) {
                state.appliedGeneration = plan.generation();
                state.applied.increment();
                appliedTotal.incrementAndGet();
                applied++;
            } else {
                state.failed.increment();
                failedTotal.incrementAndGet();
                state.previous = null;
            }
        }
        return applied;
    }

    /**
     * Stops accepting plans and drains every owner domain's queue, waiting for each dedicated
     * worker to exit. Idempotent. The shared arena/global schedulers are owned by
     * {@link WindyPitTickIntegration} and are not shut down here.
     */
    public synchronized void shutdownDrain(long timeoutMs) {
        if (closed) {
            return;
        }
        closed = true;
        final int domainCount = Math.max(1, domains.size());
        final long perDomain = Math.max(1L, timeoutMs / domainCount);
        for (Domain domain : domains) {
            scheduler.unregister(domain.region, perDomain);
        }
        domains.clear();
    }

    public List<WorldStats> stats() {
        final List<WorldStats> out = new ArrayList<WorldStats>();
        for (WorldState s : byWorld.values()) {
            out.add(new WorldStats(s.worldName, s.domain.id, s.submitted.sum(), s.planned.sum(),
                    s.applied.sum(), s.dropped.sum(), s.rejected.sum(), s.failed.sum(),
                    s.completed.size(), s.appliedGeneration));
        }
        Collections.sort(out, new java.util.Comparator<WorldStats>() {
            @Override
            public int compare(WorldStats a, WorldStats b) {
                return a.worldName().compareTo(b.worldName());
            }
        });
        return Collections.unmodifiableList(out);
    }

    public String ownerThreadName(String worldName) {
        final WorldState s = byWorld.get(worldName);
        return s == null ? null : scheduler.ownerThreadName(s.domain.region);
    }

    /** Owner region of a registered world, or null when unknown. Used by the owner ledger. */
    public OwnedRegion regionFor(String worldName) {
        final WorldState s = byWorld.get(worldName);
        return s == null ? null : s.domain.region;
    }

    public boolean isClosed() {
        return closed;
    }

    public long appliedTotal() {
        return appliedTotal.get();
    }

    public long droppedTotal() {
        return droppedTotal.get();
    }

    public long rejectedTotal() {
        return rejectedTotal.get();
    }

    public long failedTotal() {
        return failedTotal.get();
    }

    /** Number of registered owner domains (one fixed thread each). */
    public synchronized int domainCount() {
        return domains.size();
    }
}
