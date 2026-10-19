package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owner/generation ledger for world-scoped lifecycle operations (Prompt 70).
 *
 * <p>This is the only WindyPit seam allowed to answer "which thread owns this world, entity or
 * chunk, and at which world generation?". It is deliberately independent of NMS types so it can
 * be unit tested without a live server; production wiring is limited to the world registration /
 * unregistration path in {@link WindyPitTickIntegration} until the NMS hooks listed in
 * {@code docs/windypit-1.8-threaded/owner-contract.md} are proven safe.</p>
 *
 * <p>Rules enforced here:</p>
 * <ul>
 *   <li>world generation is monotonic across unload and re-register; stale handles fail fast;</li>
 *   <li>entity/chunk/player-quit operations require the world owner thread and a matching
 *       generation;</li>
 *   <li>cross-world transfer produces an immutable {@link TransferTicket}; the target owner
 *       re-validates its own generation before binding;</li>
 *   <li>a stale target is rejected, never re-bound to an arbitrary owner or the primary thread.</li>
 * </ul>
 */
public final class WorldOwnerRegistry {

    /** Current thread is not the owner of the world that owns the operated object. */
    public static final class OwnerViolationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public OwnerViolationException(String message) {
            super(message);
        }
    }

    /** World was unloaded (or re-registered) and the caller still holds an old generation. */
    public static final class StaleOwnerGenerationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public StaleOwnerGenerationException(String message) {
            super(message);
        }
    }

    /** Immutable handle binding a world name to one owner domain and one lifecycle generation. */
    public static final class WorldOwner {
        private final String worldName;
        private final String regionId;
        private final long generation;

        WorldOwner(String worldName, String regionId, long generation) {
            this.worldName = worldName;
            this.regionId = regionId;
            this.generation = generation;
        }

        public String worldName() {
            return worldName;
        }

        public String regionId() {
            return regionId;
        }

        public long generation() {
            return generation;
        }

        @Override
        public String toString() {
            return "WorldOwner{" + worldName + ", region=" + regionId + ", generation=" + generation + "}";
        }
    }

    /** Result of unloading one world; counters describe what the ledger released. */
    public static final class WorldUnload {
        private final String worldName;
        private final long retiredGeneration;
        private final long nextGeneration;
        private final int entitiesUnbound;
        private final int chunksUnloaded;

        WorldUnload(String worldName, long retiredGeneration, long nextGeneration,
                    int entitiesUnbound, int chunksUnloaded) {
            this.worldName = worldName;
            this.retiredGeneration = retiredGeneration;
            this.nextGeneration = nextGeneration;
            this.entitiesUnbound = entitiesUnbound;
            this.chunksUnloaded = chunksUnloaded;
        }

        public String worldName() {
            return worldName;
        }

        public long retiredGeneration() {
            return retiredGeneration;
        }

        public long nextGeneration() {
            return nextGeneration;
        }

        public int entitiesUnbound() {
            return entitiesUnbound;
        }

        public int chunksUnloaded() {
            return chunksUnloaded;
        }
    }

    /**
     * Immutable cross-world transfer request. Created only by {@link #beginTransfer}; the source
     * owner removes the entity before this ticket exists, and the target owner validates the
     * captured target generation before binding.
     */
    public static final class TransferTicket implements ImmutableOwnerMessage {
        private final int entityId;
        private final String fromWorld;
        private final long fromGeneration;
        private final String toWorld;
        private final long toGeneration;

        TransferTicket(int entityId, String fromWorld, long fromGeneration, String toWorld, long toGeneration) {
            this.entityId = entityId;
            this.fromWorld = fromWorld;
            this.fromGeneration = fromGeneration;
            this.toWorld = toWorld;
            this.toGeneration = toGeneration;
        }

        public int entityId() {
            return entityId;
        }

        public String fromWorld() {
            return fromWorld;
        }

        public long fromGeneration() {
            return fromGeneration;
        }

        public String toWorld() {
            return toWorld;
        }

        public long toGeneration() {
            return toGeneration;
        }

        @Override
        public String messageType() {
            return "world-transfer";
        }

        @Override
        public String toString() {
            return "TransferTicket{entity=" + entityId + ", " + fromWorld + "#" + fromGeneration
                    + " -> " + toWorld + "#" + toGeneration + "}";
        }
    }

    private static final class WorldState {
        final String worldName;
        final OwnedRegion region;
        final long generation;
        final Set<Integer> entities = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
        final Set<Long> chunks = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
        volatile boolean active = true;

        WorldState(String worldName, OwnedRegion region, long generation) {
            this.worldName = worldName;
            this.region = region;
            this.generation = generation;
        }
    }

    private final RegionScheduler scheduler;
    private final Map<String, WorldState> worlds = new ConcurrentHashMap<String, WorldState>();
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<String, AtomicLong>();
    private final Map<Integer, String> entityWorld = new ConcurrentHashMap<Integer, String>();
    private final AtomicLong ownerViolations = new AtomicLong();
    private final AtomicLong staleRejections = new AtomicLong();
    private final AtomicLong transfersCompleted = new AtomicLong();
    private final AtomicLong transfersRejected = new AtomicLong();

    public WorldOwnerRegistry(RegionScheduler scheduler) {
        if (scheduler == null) {
            throw new IllegalArgumentException("region scheduler required");
        }
        this.scheduler = scheduler;
    }

    /**
     * Registers one world owner domain. Called from server control before any owner work for the
     * world is accepted. Re-registering an active world is a programming error.
     */
    public synchronized WorldOwner registerWorld(String worldName, OwnedRegion region) {
        if (worldName == null || worldName.isEmpty()) {
            throw new IllegalArgumentException("world name required");
        }
        if (region == null) {
            throw new IllegalArgumentException("owner region required");
        }
        final WorldState existing = worlds.get(worldName);
        if (existing != null && existing.active) {
            throw new IllegalStateException("world already owned: " + worldName);
        }
        final long generation = generationCounter(worldName).incrementAndGet();
        final WorldState state = new WorldState(worldName, region, generation);
        worlds.put(worldName, state);
        return new WorldOwner(worldName, region.id(), generation);
    }

    /**
     * Unloads one world: invalidates the current generation, releases every entity/chunk binding
     * and advances the generation so a later re-register cannot collide with an old handle.
     */
    public synchronized WorldUnload unregisterWorld(String worldName) {
        final WorldState state = worlds.get(worldName);
        if (state == null || !state.active) {
            return new WorldUnload(worldName, 0L, generationCounter(worldName).get(), 0, 0);
        }
        final int entities;
        final int chunks;
        synchronized (state) {
            state.active = false;
            entities = state.entities.size();
            chunks = state.chunks.size();
            for (Integer entityId : new ArrayList<Integer>(state.entities)) {
                entityWorld.remove(entityId, worldName);
            }
            state.entities.clear();
            state.chunks.clear();
        }
        final long next = generationCounter(worldName).incrementAndGet();
        return new WorldUnload(worldName, state.generation, next, entities, chunks);
    }

    /** Returns the current owner handle, or null when the world is unknown/unloaded. */
    public WorldOwner ownerOf(String worldName) {
        final WorldState state = worlds.get(worldName);
        if (state == null || !state.active) {
            return null;
        }
        return new WorldOwner(state.worldName, state.region.id(), state.generation);
    }

    public long generationOf(String worldName) {
        return generationCounter(worldName).get();
    }

    public boolean isWorldActive(String worldName) {
        final WorldState state = worlds.get(worldName);
        return state != null && state.active;
    }

    /**
     * Validates that the current thread is the world owner and that the handle generation is the
     * live generation. Throws {@link StaleOwnerGenerationException} for a dead generation and
     * {@link OwnerViolationException} for a live world touched from a non-owner thread.
     */
    public void assertOwner(WorldOwner owner) {
        if (owner == null) {
            throw new IllegalArgumentException("world owner handle required");
        }
        final WorldState state = worlds.get(owner.worldName());
        if (state == null || !state.active) {
            staleRejections.incrementAndGet();
            throw new StaleOwnerGenerationException("world is not active: " + owner.worldName()
                    + " (generation " + owner.generation() + ")");
        }
        if (state.generation != owner.generation() || !state.region.id().equals(owner.regionId())) {
            staleRejections.incrementAndGet();
            throw new StaleOwnerGenerationException("stale world owner handle: " + owner
                    + " (live generation " + state.generation + ")");
        }
        try {
            scheduler.assertRegionThread(state.region);
        } catch (RegionScheduler.RegionThreadViolationException violation) {
            ownerViolations.incrementAndGet();
            throw new OwnerViolationException("thread " + Thread.currentThread().getName()
                    + " is not the owner of world " + owner.worldName()
                    + " (region " + owner.regionId() + ")");
        }
    }

    /** Binds a newly created entity to the world. Duplicate registration is a programming error. */
    public void registerEntity(WorldOwner owner, int entityId) {
        assertOwner(owner);
        final WorldState state = liveState(owner);
        synchronized (state) {
            requireLive(owner, state);
            final String previous = entityWorld.putIfAbsent(entityId, owner.worldName());
            if (previous != null) {
                throw new IllegalStateException("entity " + entityId + " already owned by world " + previous);
            }
            state.entities.add(entityId);
        }
    }

    /** Removes one entity binding. Idempotent; returns true when a binding was removed. */
    public boolean unregisterEntity(WorldOwner owner, int entityId) {
        assertOwner(owner);
        final WorldState state = liveState(owner);
        synchronized (state) {
            requireLive(owner, state);
            final boolean removed = entityWorld.remove(entityId, owner.worldName());
            if (removed) {
                state.entities.remove(entityId);
            }
            return removed;
        }
    }

    /** Player quit follows entity-destroy semantics on the player's world owner. */
    public boolean onPlayerQuit(WorldOwner owner, int playerEntityId) {
        return unregisterEntity(owner, playerEntityId);
    }

    /** World that currently owns the entity, or null when unbound. */
    public String entityWorld(int entityId) {
        return entityWorld.get(entityId);
    }

    public List<Integer> entitiesIn(String worldName) {
        final WorldState state = worlds.get(worldName);
        if (state == null) {
            return Collections.emptyList();
        }
        synchronized (state) {
            return Collections.unmodifiableList(new ArrayList<Integer>(state.entities));
        }
    }

    /** Registers one loaded chunk for the world. Returns true when it was not already present. */
    public boolean registerChunk(WorldOwner owner, int chunkX, int chunkZ) {
        assertOwner(owner);
        final WorldState state = liveState(owner);
        synchronized (state) {
            requireLive(owner, state);
            return state.chunks.add(chunkKey(chunkX, chunkZ));
        }
    }

    /** Unloads one chunk from the world. Idempotent; returns true when it was present. */
    public boolean unloadChunk(WorldOwner owner, int chunkX, int chunkZ) {
        assertOwner(owner);
        final WorldState state = liveState(owner);
        synchronized (state) {
            requireLive(owner, state);
            return state.chunks.remove(chunkKey(chunkX, chunkZ));
        }
    }

    public boolean isChunkLoaded(String worldName, int chunkX, int chunkZ) {
        final WorldState state = worlds.get(worldName);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.chunks.contains(chunkKey(chunkX, chunkZ));
        }
    }

    public int chunkCount(String worldName) {
        final WorldState state = worlds.get(worldName);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.chunks.size();
        }
    }

    /**
     * Starts a cross-world transfer on the source owner. The entity is removed from the source
     * ledger immediately; the returned ticket is the only data allowed to cross to the target.
     */
    public TransferTicket beginTransfer(WorldOwner source, int entityId, String targetWorld) {
        assertOwner(source);
        final WorldState from = liveState(source);
        final WorldState to = worlds.get(targetWorld);
        if (to == null || !to.active) {
            throw new IllegalStateException("target world is not owned: " + targetWorld);
        }
        synchronized (from) {
            requireLive(source, from);
            if (!from.entities.contains(entityId)) {
                throw new IllegalStateException("entity " + entityId + " is not owned by world " + source.worldName());
            }
            entityWorld.remove(entityId, source.worldName());
            from.entities.remove(entityId);
        }
        return new TransferTicket(entityId, source.worldName(), source.generation(), targetWorld, to.generation);
    }

    /**
     * Completes a transfer on the target owner. Returns false (and counts a rejection) when the
     * target world was unloaded/re-registered after {@link #beginTransfer}; the entity is already
     * detached from the source and must be destroyed by the caller, never re-bound elsewhere.
     */
    public boolean completeTransfer(TransferTicket ticket) {
        if (ticket == null) {
            throw new IllegalArgumentException("transfer ticket required");
        }
        final WorldState to = worlds.get(ticket.toWorld());
        if (to == null || !to.active || to.generation != ticket.toGeneration()) {
            transfersRejected.incrementAndGet();
            staleRejections.incrementAndGet();
            return false;
        }
        final WorldOwner target = new WorldOwner(to.worldName, to.region.id(), to.generation);
        assertOwner(target);
        synchronized (to) {
            requireLive(target, to);
            final String previous = entityWorld.putIfAbsent(ticket.entityId(), ticket.toWorld());
            if (previous != null) {
                transfersRejected.incrementAndGet();
                throw new IllegalStateException("entity " + ticket.entityId() + " already owned by world " + previous);
            }
            to.entities.add(ticket.entityId());
        }
        transfersCompleted.incrementAndGet();
        return true;
    }

    public long ownerViolations() {
        return ownerViolations.get();
    }

    public long staleRejections() {
        return staleRejections.get();
    }

    public long transfersCompleted() {
        return transfersCompleted.get();
    }

    public long transfersRejected() {
        return transfersRejected.get();
    }

    public int worldCount() {
        int count = 0;
        for (WorldState state : worlds.values()) {
            if (state.active) {
                count++;
            }
        }
        return count;
    }

    private WorldState liveState(WorldOwner owner) {
        final WorldState state = worlds.get(owner.worldName());
        if (state == null || !state.active) {
            staleRejections.incrementAndGet();
            throw new StaleOwnerGenerationException("world is not active: " + owner.worldName());
        }
        return state;
    }

    private void requireLive(WorldOwner owner, WorldState state) {
        if (!state.active || state.generation != owner.generation()) {
            staleRejections.incrementAndGet();
            throw new StaleOwnerGenerationException("world generation changed during operation: " + owner);
        }
    }

    private AtomicLong generationCounter(String worldName) {
        AtomicLong counter = generations.get(worldName);
        if (counter == null) {
            counter = generations.computeIfAbsent(worldName, new java.util.function.Function<String, AtomicLong>() {
                @Override
                public AtomicLong apply(String key) {
                    return new AtomicLong();
                }
            });
        }
        return counter;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
