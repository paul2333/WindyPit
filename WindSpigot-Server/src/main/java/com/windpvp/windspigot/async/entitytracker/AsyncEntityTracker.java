package com.windpvp.windspigot.async.entitytracker;

import net.minecraft.server.EntityTracker;
import net.minecraft.server.WorldServer;

/**
 * Retained for existing world tracker construction. Entity tracking mutates
 * Bukkit/NMS state and can fire plugin events, so updates use EntityTracker's
 * synchronous implementation on the server thread.
 */
public class AsyncEntityTracker extends EntityTracker {

	public AsyncEntityTracker(WorldServer worldserver) {
		super(worldserver);
	}
}
