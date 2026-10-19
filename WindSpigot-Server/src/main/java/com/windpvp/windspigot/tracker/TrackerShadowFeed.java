
package com.windpvp.windspigot.tracker;

import com.windpvp.windspigot.scheduler.TrackerSnapshot;
import net.minecraft.server.Entity;
import net.minecraft.server.EntityArrow;
import net.minecraft.server.EntityFishingHook;
import net.minecraft.server.EntityHuman;
import net.minecraft.server.EntityPlayer;
import net.minecraft.server.EntityPotion;
import net.minecraft.server.EntityTNTPrimed;
import net.minecraft.server.WorldServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Shadow-mode tracker snapshot feed (W2-TRACKER). Reads entity and viewer data from NMS
 * WorldServer collections and builds immutable TrackerSnapshots for the async planning engine.
 *
 * Shadow mode: snapshots are built and handed to the engine, but plan results are NOT applied
 * to the real tracker. This validates the data pipeline without changing gameplay behavior.
 *
 * Entity typeId mapping (1.8.8 entity IDs for low-latency types):
 *   50 = TNTPrimed, 60 = Arrow, 90 = FishingHook, 73 = Potion. All others = 0.
 */
public final class TrackerShadowFeed {

    private static final int VIEW_DISTANCE_BLOCKS = 48;

    private TrackerShadowFeed() {}

    /** Builds a TrackerSnapshot from a WorldServer's entity and player lists. */
    public static TrackerSnapshot buildSnapshot(WorldServer worldserver, long generation, String regionId) {
        final List<TrackerSnapshot.EntityView> entities = new ArrayList<>();
        final List<TrackerSnapshot.ViewerView> viewers = new ArrayList<>();

        for (Entity entity : worldserver.entityList) {
            if (entity == null) continue;
            entities.add(new TrackerSnapshot.EntityView(
                    entity.getId(),
                    resolveTypeId(entity),
                    entity.locX, entity.locY, entity.locZ,
                    entity.yaw, entity.pitch,
                    entity.dead));
        }

        for (EntityHuman human : worldserver.players) {
            if (human instanceof EntityPlayer) {
                viewers.add(new TrackerSnapshot.ViewerView(
                        human.getId(),
                        human.locX, human.locY, human.locZ,
                        VIEW_DISTANCE_BLOCKS));
            }
        }

        return new TrackerSnapshot(generation, regionId, entities, viewers);
    }

    /** Resolves the low-latency entity type ID via instanceof; 0 for all other types. */
    public static int resolveTypeId(Entity entity) {
        if (entity instanceof EntityTNTPrimed) return 50;
        if (entity instanceof EntityArrow) return 60;
        if (entity instanceof EntityFishingHook) return 90;
        if (entity instanceof EntityPotion) return 73;
        return 0;
    }

    /** Feeds all non-null worlds: builds snapshots and logs plan submission (shadow mode). */
    public static void feed(WorldServer[] worlds) {
        if (worlds == null) return;
        long gen = System.nanoTime();
        for (int i = 0; i < worlds.length; i++) {
            final WorldServer ws = worlds[i];
            if (ws == null) continue;
            final TrackerSnapshot snapshot = buildSnapshot(ws, gen, "world-" + i);
            // shadow mode: snapshot is built but plans are not applied to the real tracker
            // TrackerAsyncEngine integration for actual planning goes here in a later revision
        }
    }
}

