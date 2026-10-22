package com.windpvp.windspigot.scheduler;

import com.windpvp.windspigot.config.WindyPitConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Host-side holder of the WindyPit scheduling backbone. MinecraftServer owns one instance and
 * calls {@link #onTickSeam()} once per server tick on the primary thread, after world ticks.
 *
 * <p>Two execution modes:</p>
 * <ul>
 *   <li><b>COMPAT</b>: no dedicated world owner threads are created, the shared arena is pumped
 *       on the primary thread and the vanilla tracker path is untouched.</li>
 *   <li><b>REGION</b> (default): dedicated {@link WorldRegionRuntime} owner domains are created
 *       lazily per registered world, capped by {@code threading.region-threads}. Each world's
 *       owner thread runs real {@link TrackerVisibilityPlanner} work against immutable snapshots;
 *       the primary thread applies the resulting plan to the NMS tracker. The shared ThePit arena
 *       remains a single owner (pumped on the primary thread by default).</li>
 * </ul>
 *
 * <p>NMS/Bukkit objects never leave the primary thread: only immutable {@link TrackerSnapshot}
 * data is handed to an owner thread, and only immutable plans come back.</p>
 */
public final class WindyPitTickIntegration {

    private static final Logger LOGGER = Logger.getLogger("WindyPit");

    private final RegionScheduler regionScheduler = new RegionScheduler();
    private final WorldOwnerRegistry worldOwners;
    private final GlobalRegionScheduler globalScheduler;
    private final EntityScheduler entityScheduler;
    private final ComputePool computePool;
    private final OwnedRegion arena;
    private final TickPipeline pipeline;
    private final WindyPitConfig config;
    private final WorldRegionRuntime worldRegions;
    private volatile boolean drainDone;
    private long seamCount;
    private final AtomicLong snapshotGeneration = new AtomicLong(1L);
    private final AtomicLong computeSubmitted = new AtomicLong();
    private final AtomicLong computeCompleted = new AtomicLong();
    private final AtomicLong computeStaleRejected = new AtomicLong();

    // Reference to worlds for snapshot; injected after construction
    private volatile WorldServer[] worlds;

    public WindyPitTickIntegration(WindyPitConfig config) {
        this.config = config;

        // Apply arena-owner config
        if (config.rollback().forcePrimaryThreadArena() || config.threading().arenaOwner() == WindyPitConfig.ArenaOwner.PRIMARY) {
            this.arena = OwnedRegion.arenaPrimary("arena", 4096);
        } else {
            this.arena = OwnedRegion.dedicated("arena", RegionKind.ARENA, 4096);
        }

        regionScheduler.register(arena);

        // Owner/generation ledger for world-scoped lifecycle operations (Prompt 70). Only the
        // world register/unregister path is wired here; NMS entity/chunk/quit/transfer hooks stay
        // on the primary thread until the gaps in owner-contract.md are closed.
        this.worldOwners = new WorldOwnerRegistry(regionScheduler);

        // Apply threading config to schedulers
        this.globalScheduler = new GlobalRegionScheduler(4096, 4096, regionScheduler.diagnostics());
        this.entityScheduler = new EntityScheduler(regionScheduler, regionScheduler.diagnostics());

        // Apply compute-threads config
        // 【2026-10-22】auto 从 cores/2 改为 clamp(cores-1, 1, 8)：
        // 纯计算池只该给主线程留 1 核，而不是把一半核心闲置。
        final int cores = Runtime.getRuntime().availableProcessors();
        int computeThreads = config.threading().resolveThreads(config.threading().computeThreads(),
            Math.max(1, Math.min(8, cores - 1)));
        this.computePool = new ComputePool(computeThreads, 1024, regionScheduler, new ComputePool.StaleCheck() {
            @Override
            public boolean isCurrent(String regionId, long generation, int entityId, long sequence) {
                return generation >= snapshotGeneration.get() - 1;
            }
        });

        // REGION mode: real per-world owner domains doing tracker visibility planning.
        final boolean regionMode = config.threading().mode() == WindyPitConfig.ThreadingMode.REGION
                && config.features().asyncEntityTracking()
                && !config.rollback().disableAsyncTracking()
                && !config.rollback().disableRegionScheduler();
        final int domains = regionMode ? Math.max(1, config.threading().regionThreads()) : 0;
        if (regionMode) {
            this.worldRegions = new WorldRegionRuntime(regionScheduler,
                    new TrackerVisibilityPlanner(TrackerVisibilityPlanner.defaultLowLatencyTypeIds(), null),
                    new NmsTrackerPlanApplier(), domains, 4);
        } else {
            this.worldRegions = null;
        }

        // 【2026-10-22】把"多核到底有没有生效"打成一行启动日志。
        // 之前只能靠插件侧的反射推断，运维看到 PUMPED_PRIMARY 也无法确认是配置没生效
        // 还是探测失败。这里由服务端自己说清楚：核数、arena 执行模式、各池线程数。
        LOGGER.info("WindyPit threading: cores=" + cores
                + ", mode=" + config.threading().mode()
                + ", arena=" + arena.mode()
                + (arena.mode() == ExecutionMode.DEDICATED_THREAD
                        ? "(worker=WindyPit-Region-arena)" : "(pumped by primary thread)")
                + ", regionMode=" + regionMode
                + ", regionThreads=" + domains
                + ", computeThreads=" + computeThreads
                + " (config=" + config.threading().computeThreads() + ")"
                + ", rollback{forcePrimaryArena=" + config.rollback().forcePrimaryThreadArena()
                + ", disableRegionScheduler=" + config.rollback().disableRegionScheduler() + "}");

        pipeline = new TickPipeline(new TickPipeline.Target() {
            @Override
            public void serverControl() {
                // host runs server control before world ticks
            }

            @Override
            public Object regionSnapshot() {
                // Per-world snapshots are built inside WorldTicker at the exact post-tick seam.
                return null;
            }

            @Override
            public void asyncCompute(Object snapshot) {
                // Owner domains receive work at the WorldTicker seam; no synthetic jobs here.
            }

            @Override
            public void ownerCommit() {
                // A dedicated arena is drained by its own fixed worker; only the primary-pumped
                // arena may be pumped from the tick pipeline. Pumping a dedicated region would
                // throw and break shutdown when arena-owner=dedicated.
                if (arena.mode() == ExecutionMode.PUMPED_PRIMARY) {
                    regionScheduler.pumpOnce(arena);
                }
            }
        });
    }

    public WindyPitConfig config() {
        return config;
    }

    /** Inject world references; registers REGION owner domains when enabled. */
    public void setWorlds(WorldServer[] worlds) {
        this.worlds = worlds;
        syncWorlds();
    }

    private void syncWorlds() {
        // MinecraftServer.worldServer is a pre-allocated array that is not always populated;
        // the authoritative live list is MinecraftServer.worlds. Prefer it and fall back to the
        // injected array so both startup and dynamic world creation register owner domains.
        final List<WorldServer> source = new ArrayList<WorldServer>();
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null && server.worlds != null) {
            source.addAll(server.worlds);
        }
        final WorldServer[] ws = this.worlds;
        if (source.isEmpty() && ws != null) {
            for (WorldServer world : ws) {
                if (world != null) {
                    source.add(world);
                }
            }
        }
        if (source.isEmpty()) {
            return;
        }
        final List<String> live = new ArrayList<String>();
        for (WorldServer world : source) {
            if (world == null) {
                continue;
            }
            final String name = world.getWorld().getName();
            live.add(name);
            if (worldRegions != null) {
                if (worldRegions.registerWorld(name)) {
                    final OwnedRegion region = worldRegions.regionFor(name);
                    if (region != null) {
                        worldOwners.registerWorld(name, region);
                    }
                }
                // Only worlds with an owner domain get plan-driven visibility.
                world.getTracker().setPlanDrivenVisibility(worldRegions.isRegistered(name));
            }
        }
        if (worldRegions != null) {
            final List<String> retired = worldRegions.retainWorlds(live);
            for (String worldName : retired) {
                worldOwners.unregisterWorld(worldName);
            }
        }
    }

    /** Called once per server tick from MinecraftServer, on the primary thread. */
    public void onTickSeam() {
        if (drainDone) {
            return;
        }
        seamCount++;
        snapshotGeneration.incrementAndGet();
        syncWorlds();
        globalScheduler.tick();
        pipeline.runSegment(TickPipeline.Phase.REGION_SNAPSHOT, TickPipeline.Phase.OWNER_COMMIT);
    }

    /** Shutdown drain: stop owners, drain accepted work, close pools. Idempotent. */
    public void shutdownDrain(long timeoutMs) throws InterruptedException {
        if (drainDone) {
            return;
        }
        drainDone = true;
        pipeline.runSegment(TickPipeline.Phase.SHUTDOWN_DRAIN, TickPipeline.Phase.SHUTDOWN_DRAIN);

        // Stop accepting plan work and drain every world owner first.
        if (worldRegions != null) {
            worldRegions.shutdownDrain(Math.max(1L, timeoutMs / 3));
        }
        final WorldServer[] ws = this.worlds;
        if (ws != null) {
            for (WorldServer world : ws) {
                if (world != null) {
                    world.getTracker().setPlanDrivenVisibility(false);
                }
            }
        }

        // Close compute pool first
        computePool.closeDiscardQueued();

        // Then drain schedulers
        regionScheduler.shutdownDrain(Math.max(1L, timeoutMs / 2));
        globalScheduler.shutdownDrainInline();

        // Finally await compute workers
        if (!computePool.awaitTermination(Math.max(1L, timeoutMs / 2))) {
            throw new IllegalStateException("compute pool did not terminate in time");
        }
    }

    public RegionScheduler regionScheduler() {
        return regionScheduler;
    }

    public GlobalRegionScheduler globalScheduler() {
        return globalScheduler;
    }

    public EntityScheduler entityScheduler() {
        return entityScheduler;
    }

    public ComputePool computePool() {
        return computePool;
    }

    public OwnedRegion arena() {
        return arena;
    }

    /** Owner/generation ledger for world-scoped lifecycle operations (Prompt 70). */
    public WorldOwnerRegistry worldOwners() {
        return worldOwners;
    }

    public TickPipeline pipeline() {
        return pipeline;
    }

    /** Null in COMPAT mode. */
    public WorldRegionRuntime worldRegions() {
        return worldRegions;
    }

    public long seamCount() {
        return seamCount;
    }

    public ComputePool.Stats computeStats() {
        return computePool.stats();
    }

    public long snapshotGeneration() {
        return snapshotGeneration.get();
    }

    public long computeSubmittedCount() {
        return computeSubmitted.get();
    }

    public long computeCompletedCount() {
        return computeCompleted.get();
    }

    public long computeStaleRejectedCount() {
        return computeStaleRejected.get();
    }
}
