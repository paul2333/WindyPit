package com.windpvp.windspigot.scheduler;

import net.minecraft.server.EntityHuman;
import net.minecraft.server.EntityPlayer;
import net.minecraft.server.EntityTracker;
import net.minecraft.server.EntityTrackerEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldServer;

import java.util.List;
import java.util.Map;

/**
 * Production {@link WorldRegionRuntime.PlanApplier}: applies an immutable visibility plan to the
 * live 1.8.8 {@link EntityTracker} on the primary thread.
 *
 * <p>Only add/remove pairs are applied here. Movement, velocity, head-rotation and riding packets
 * remain on the vanilla {@code EntityTrackerEntry.track} path, which is invoked exactly once per
 * tick by {@code EntityTracker.updatePlayers} in movement-only mode. Both {@code updatePlayer} and
 * {@code clear} are idempotent, so a plan that is replayed after a dropped tick converges instead
 * of duplicating spawn/despawn packets.</p>
 */
public final class NmsTrackerPlanApplier implements WorldRegionRuntime.PlanApplier {

    @Override
    public boolean apply(String worldName, TrackerVisibilityPlanner.Plan plan) {
        if (plan == null) {
            return false;
        }
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return false;
        }
        final WorldServer world = findWorld(server, worldName);
        if (world == null) {
            return false;
        }
        final EntityTracker tracker = world.getTracker();
        if (tracker == null) {
            return false;
        }

        applyAdds(world, tracker, plan.add());
        applyRemoves(world, tracker, plan.remove());
        return true;
    }

    private static void applyAdds(WorldServer world, EntityTracker tracker, Map<Integer, List<Integer>> adds) {
        for (Map.Entry<Integer, List<Integer>> viewerEntry : adds.entrySet()) {
            final EntityPlayer viewer = findViewer(world, viewerEntry.getKey());
            if (viewer == null) {
                continue;
            }
            for (Integer entityId : viewerEntry.getValue()) {
                final EntityTrackerEntry entry = tracker.trackedEntities.get(entityId.intValue());
                if (entry != null) {
                    // Idempotent: already-tracked viewers are ignored by the entry itself.
                    entry.updatePlayer(viewer);
                }
            }
        }
    }

    private static void applyRemoves(WorldServer world, EntityTracker tracker, Map<Integer, List<Integer>> removes) {
        for (Map.Entry<Integer, List<Integer>> viewerEntry : removes.entrySet()) {
            final EntityPlayer viewer = findViewer(world, viewerEntry.getKey());
            if (viewer == null) {
                continue;
            }
            for (Integer entityId : viewerEntry.getValue()) {
                final EntityTrackerEntry entry = tracker.trackedEntities.get(entityId.intValue());
                if (entry != null) {
                    entry.clear(viewer);
                }
            }
        }
    }

    private static WorldServer findWorld(MinecraftServer server, String worldName) {
        for (WorldServer world : server.worlds) {
            if (world != null && world.getWorld().getName().equals(worldName)) {
                return world;
            }
        }
        return null;
    }

    private static EntityPlayer findViewer(WorldServer world, int entityId) {
        for (EntityHuman human : world.players) {
            if (human instanceof EntityPlayer && human.getId() == entityId) {
                return (EntityPlayer) human;
            }
        }
        return null;
    }
}
