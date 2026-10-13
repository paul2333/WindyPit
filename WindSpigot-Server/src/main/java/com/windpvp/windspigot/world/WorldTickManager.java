package com.windpvp.windspigot.world;

import co.aikar.timings.SpigotTimings;
import net.minecraft.server.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldServer;

public class WorldTickManager {

	// Instance
	private static WorldTickManager worldTickerManagerInstance;
		
	// Initializes the world ticker manager
	public WorldTickManager() {
		worldTickerManagerInstance = this;

	}

	// Ticks all worlds
	public void tick() {
		tickWorlds();
	}
	
	private void tickWorlds() {
        // Move BukkitScheduler stuff here so async entity tracking does not interfere
        SpigotTimings.bukkitSchedulerTimer.startTiming(); // Spigot
        // CraftBukkit start
        MinecraftServer.getServer().server.getScheduler().mainThreadHeartbeat(MinecraftServer.getServer().at());
        SpigotTimings.bukkitSchedulerTimer.stopTiming(); // Spigot
	        
		for (int i = 0; i < MinecraftServer.getServer().worlds.size(); i++) {
			WorldServer world = MinecraftServer.getServer().worlds.get(i);
			world.ticker.run();
		}

		// Entity tracking is world-local, but each connection has one packet queue.
		// Flush that global set once after all worlds have produced their updates.
		for (EntityPlayer player : MinecraftServer.getServer().getPlayerList().players) {
			player.playerConnection.sendQueuedPackets();
		}
	}

	/*
	 * The world ticker manager instance
	 */
	public static WorldTickManager getInstance() {
		return worldTickerManagerInstance;
	}
}
