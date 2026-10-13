package com.falchus.spigot.optimizations;

import com.windpvp.windspigot.config.WindSpigotConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.server.NetworkManager;
import net.minecraft.server.Packet;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.BlockingQueue;

public class FastNetworkManager {

	private final NetworkManager networkManager;
	private final BlockingQueue<Packet<?>>[] queues;
	private final AtomicLong queuedPackets = new AtomicLong();
	private final AtomicLong droppedPackets = new AtomicLong();

	public FastNetworkManager(NetworkManager networkManager) {
		this.networkManager = networkManager;
		int queueCount = Math.max(1, WindSpigotConfig.trackingThreads);
		int queueCapacity = Math.max(64, WindSpigotConfig.trackingQueueCapacity);
		@SuppressWarnings("unchecked")
		BlockingQueue<Packet<?>>[] configuredQueues = new BlockingQueue[queueCount];
		for (int i = 0; i < configuredQueues.length; i++) {
			configuredQueues[i] = new ArrayBlockingQueue<>(queueCapacity);
		}
		this.queues = configuredQueues;
	}

	public void writePacketLazily(Packet<?> packet, boolean flush) {
		Channel channel = networkManager.channel;
		if (channel == null || !channel.isActive()) return;

		channel.eventLoop().execute(() -> {
			ChannelFuture future = channel.write(packet);
			future.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
			if (flush) {
				channel.flush();
			}
		});
	}

	public void writePacketLazily(Packet<?> packet) {
		writePacketLazily(packet, false);
	}

	public void queuePacket(Packet<?> packet, int trackerThread) {
		if (packet == null) return;
		int queueIndex = Math.floorMod(trackerThread, queues.length);
		queuedPackets.incrementAndGet();
		if (queues[queueIndex].offer(packet)) {
			return;
		} else {
			queuedPackets.decrementAndGet();
			// Tracking packets are refreshed on the next tick. Dropping only when the
			// bounded queue is saturated prevents an unbounded memory spiral while
			// retaining the normal synchronous packet path for gameplay-critical sends.
			droppedPackets.incrementAndGet();
		}
	}

	public void flushQueuedPackets() {
		Channel channel = networkManager.channel;
		if (channel == null || !channel.isActive()) return;

		ArrayList<Packet<?>> writing = new ArrayList<>();
		Packet<?> packet;
		for (int i = 0; i < queues.length; i++) {
			BlockingQueue<Packet<?>> current = queues[i];
			while ((packet = current.poll()) != null) {
				queuedPackets.decrementAndGet();
				writing.add(packet);
			}
		}
		if (writing.isEmpty()) return;

		channel.eventLoop().execute(() -> {
			for (Packet<?> p : writing) {
				ChannelFuture future = channel.write(p);
				future.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
			}
			channel.flush();
		});
	}

	/** Discards tracker packets when a connection closes before the next tick. */
	public void discardQueuedPackets() {
		for (BlockingQueue<Packet<?>> queue : queues) {
			Packet<?> packet;
			while ((packet = queue.poll()) != null) {
				queuedPackets.decrementAndGet();
			}
		}
	}

	public int queuedPacketCount() {
		long count = queuedPackets.get();
		return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, count);
	}

	public long droppedPacketCount() {
		return droppedPackets.get();
	}
}
