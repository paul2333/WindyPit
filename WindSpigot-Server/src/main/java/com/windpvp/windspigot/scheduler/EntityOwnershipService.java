
package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity owner lifecycle over the region scheduler: creation, tracking region binding,
 * migration, destruction, player quit, world unload and owner rebind. Pending work of a
 * departing entity is always retired; lifecycle mutations assert region ownership through
 * {@link RegionScheduler#assertRegionThread} where the caller context is an owner.
 */
public final class EntityOwnershipService {

    private final EntityScheduler entities;
    private final RegionScheduler regions;
    private final Map<Integer, OwnedRegion> entityRegion = new ConcurrentHashMap<Integer, OwnedRegion>();
    private final Map<String, List<Integer>> regionEntities = new ConcurrentHashMap<String, List<Integer>>();

    public EntityOwnershipService(RegionScheduler regions, EntityScheduler entities) {
        this.regions = regions;
        this.entities = entities;
    }

    /** Binds a newly created entity to a region. */
    public void onEntityCreated(int entityId, OwnedRegion region) {
        if (entityRegion.putIfAbsent(entityId, region) != null) {
            throw new IllegalStateException("entity already owned: " + entityId);
        }
        List<Integer> list = regionEntities.get(region.id());
        if (list == null) {
            list = regionEntities.computeIfAbsent(region.id(), k -> Collections.synchronizedList(new ArrayList<Integer>()));
        }
        list.add(entityId);
        entities.registerEntity(entityId, region);
    }

    public OwnedRegion regionOf(int entityId) {
        return entityRegion.get(entityId);
    }

    public List<Integer> entitiesInRegion(OwnedRegion region) {
        final List<Integer> l = regionEntities.get(region.id());
        return l == null ? Collections.<Integer>emptyList() : new ArrayList<Integer>(l);
    }

    /** Retires pending work and unbinds. Idempotent. @return retired pending-task count */
    public int onEntityDestroyed(int entityId) {
        final OwnedRegion region = entityRegion.remove(entityId);
        if (region == null) {
            return 0;
        }
        final int retired = entities.unregisterEntity(entityId);
        final List<Integer> l = regionEntities.get(region.id());
        if (l != null) {
            l.remove(Integer.valueOf(entityId));
        }
        return retired;
    }

    /** Moves the entity to a new region: old pending work retired, binding replaced. */
    public int onEntityMigrated(int entityId, OwnedRegion newRegion) {
        final OwnedRegion old = entityRegion.get(entityId);
        if (old == null) {
            throw new IllegalStateException("entity not owned: " + entityId);
        }
        if (old.id().equals(newRegion.id())) {
            return 0;
        }
        final int retired = onEntityDestroyed(entityId);
        onEntityCreated(entityId, newRegion);
        return retired;
    }

    /** Player quit follows destroy semantics. */
    public int onPlayerQuit(int playerEntityId) {
        return onEntityDestroyed(playerEntityId);
    }

    /** World unload: destroys every entity bound to the given regions. @return destroyed entity count */
    public int onWorldUnload(Iterable<OwnedRegion> unloadedRegions) {
        int destroyed = 0;
        for (OwnedRegion region : unloadedRegions) {
            for (Integer id : entitiesInRegion(region)) {
                onEntityDestroyed(id);
                destroyed++;
            }
        }
        return destroyed;
    }

    /** Runtime assertion: current thread must own the entity's region. Violations are recorded. */
    public void checkOwner(int entityId) {
        final OwnedRegion region = entityRegion.get(entityId);
        if (region == null) {
            throw new IllegalStateException("entity not owned: " + entityId);
        }
        regions.assertRegionThread(region);
    }

    /** Schedules work on the entity's region owner thread. */
    public TaskRetirementToken scheduleWork(int entityId, Runnable body) {
        final OwnedRegion region = entityRegion.get(entityId);
        if (region == null) {
            throw new IllegalStateException("entity not owned: " + entityId);
        }
        return entities.scheduleForEntity(entityId, body);
    }
}

