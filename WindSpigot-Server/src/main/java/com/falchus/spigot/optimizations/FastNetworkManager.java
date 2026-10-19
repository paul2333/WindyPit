package com.falchus.spigot.optimizations;

import com.windpvp.windspigot.config.WindSpigotConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.server.NetworkManager;
import net.minecraft.server.Packet;
import net.minecraft.server.ChatComponentText;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.BlockingQueue;

public class FastNetworkManager {

	/** Bounded wait used by {@link #drainAll()} when no explicit timeout is supplied. */
	public static final long DEFAULT_DRAIN_TIMEOUT_MS = 5000L;

	private final NetworkManager networkManager;
	private final BlockingQueue<Packet<?>>[] queues;
	private final AtomicLong queuedPackets = new AtomicLong();
	private final AtomicLong droppedPackets = new AtomicLong();
	private final AtomicLong discardedPackets = new AtomicLong();
	private final AtomicLong rejectedPackets = new AtomicLong();
	private volatile boolean drainCompleted;

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
		if (drainCompleted) {
			rejectedPackets.incrementAndGet();
			return;
		}
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
		if (drainCompleted) {
			rejectedPackets.incrementAndGet();
			return;
		}
		Channel channel = networkManager.channel;
		if (channel != null && !channel.isOpen()) {
			rejectedPackets.incrementAndGet();
			return;
		}
		int queueIndex = Math.floorMod(trackerThread, queues.length);
		queuedPackets.incrementAndGet();
		if (queues[queueIndex].offer(packet)) {
			return;
		} else {
			queuedPackets.decrementAndGet();
			// Spawn packets and relative movement deltas cannot safely be dropped:
			// disconnect instead of letting the client continue with missing state.
			droppedPackets.incrementAndGet();
			if (channel != null) {
				networkManager.close(new ChatComponentText("Connection overloaded; please reconnect."));
			} else {
				discardQueuedPackets();
			}
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

	/**
	 * Shutdown drain: stops accepting tracker frames for this connection, flushes every frame that
	 * was already accepted exactly once, and returns a statistics snapshot. A connection that is
	 * already closed has its accepted frames counted as discarded instead of silently dropped.
	 */
	public DrainStats drainAll() {
		return drainAll(DEFAULT_DRAIN_TIMEOUT_MS);
	}

	public DrainStats drainAll(long timeoutMs) {
		drainCompleted = true;
		DrainStats stats = new DrainStats();
		stats.connections = 1;
		stats.rejectedAfterDrainPackets = rejectedPackets.get();
		stats.overloadDroppedPackets = droppedPackets.get();
		stats.totalDiscardedPackets = discardedPackets.get();

		Channel channel = networkManager.channel;
		ArrayList<Packet<?>> writing = new ArrayList<>();
		for (BlockingQueue<Packet<?>> queue : queues) {
			Packet<?> packet;
			while ((packet = queue.poll()) != null) {
				queuedPackets.decrementAndGet();
				writing.add(packet);
			}
		}
		stats.acceptedPackets = writing.size();

		if (writing.isEmpty()) {
			stats.remainingQueuedPackets = queuedPacketCount();
			return stats;
		}
		if (channel == null || !channel.isOpen() || !channel.isActive()) {
			discardPackets(writing);
			stats.closedConnections = 1;
			stats.discardedPackets = writing.size();
			stats.totalDiscardedPackets = discardedPackets.get();
			stats.remainingQueuedPackets = queuedPacketCount();
			return stats;
		}

		final long timeout = Math.max(1L, timeoutMs);
		try {
			ChannelFuture last = null;
			for (Packet<?> packet : writing) {
				last = channel.write(packet);
				last.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
			}
			channel.flush();
			stats.drainedConnections = 1;
			if (last != null && !channel.eventLoop().inEventLoop()) {
				if (!last.await(timeout, TimeUnit.MILLISECONDS)) {
					stats.timedOut = true;
					stats.failedConnections = 1;
					stats.unconfirmedPackets = writing.size();
					stats.failure = new TimeoutException("network drain timed out after " + timeout + "ms");
					stats.remainingQueuedPackets = queuedPacketCount();
					return stats;
				}
				if (!last.isSuccess()) {
					stats.failedConnections = 1;
					stats.unconfirmedPackets = writing.size();
					stats.failure = last.cause();
					stats.remainingQueuedPackets = queuedPacketCount();
					return stats;
				}
			}
			stats.flushedPackets = writing.size();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			stats.timedOut = true;
			stats.failedConnections = 1;
			stats.unconfirmedPackets = writing.size();
			stats.failure = interrupted;
		} catch (RuntimeException runtime) {
			stats.failedConnections = 1;
			stats.unconfirmedPackets = writing.size();
			stats.failure = runtime;
		}
		stats.remainingQueuedPackets = queuedPacketCount();
		return stats;
	}

	/**
	 * Shutdown drain across every connection. Continues after closed/failed connections so one bad
	 * connection cannot hide the state of the rest.
	 */
	public static DrainStats drainAll(Iterable<NetworkManager> connections) {
		return drainAll(connections, DEFAULT_DRAIN_TIMEOUT_MS);
	}

	public static DrainStats drainAll(Iterable<NetworkManager> connections, long timeoutMs) {
		DrainStats total = new DrainStats();
		if (connections == null) {
			return total;
		}
		final long deadline = System.nanoTime() + Math.max(1L, timeoutMs) * 1000000L;
		for (NetworkManager connection : connections) {
			if (connection == null) {
				continue;
			}
			final long remaining = Math.max(1L, (deadline - System.nanoTime()) / 1000000L);
			total.add(connection.fastNetworkManager.drainAll(remaining));
		}
		return total;
	}

	private void discardPackets(ArrayList<Packet<?>> packets) {
		if (packets.isEmpty()) {
			return;
		}
		discardedPackets.addAndGet(packets.size());
	}

	/** Discards tracker packets when a connection closes before the next tick. */
	public void discardQueuedPackets() {
		for (BlockingQueue<Packet<?>> queue : queues) {
			Packet<?> packet;
			while ((packet = queue.poll()) != null) {
				queuedPackets.decrementAndGet();
				discardedPackets.incrementAndGet();
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

	public long discardedPacketCount() {
		return discardedPackets.get();
	}

	public long rejectedPacketCount() {
		return rejectedPackets.get();
	}

	/** Statistics for one connection drain or an aggregate across connections. */
	public static final class DrainStats {
		private int connections;
		private int drainedConnections;
		private int closedConnections;
		private int failedConnections;
		private long acceptedPackets;
		private long flushedPackets;
		private long discardedPackets;
		private long unconfirmedPackets;
		private long rejectedAfterDrainPackets;
		private long overloadDroppedPackets;
		private long totalDiscardedPackets;
		private long remainingQueuedPackets;
		private boolean timedOut;
		private Throwable failure;

		void add(DrainStats other) {
			if (other == null) {
				return;
			}
			connections += other.connections;
			drainedConnections += other.drainedConnections;
			closedConnections += other.closedConnections;
			failedConnections += other.failedConnections;
			acceptedPackets += other.acceptedPackets;
			flushedPackets += other.flushedPackets;
			discardedPackets += other.discardedPackets;
			unconfirmedPackets += other.unconfirmedPackets;
			rejectedAfterDrainPackets += other.rejectedAfterDrainPackets;
			overloadDroppedPackets += other.overloadDroppedPackets;
			totalDiscardedPackets += other.totalDiscardedPackets;
			remainingQueuedPackets += other.remainingQueuedPackets;
			timedOut |= other.timedOut;
			if (other.failure != null) {
				failure = other.failure;
			}
		}

		public int connections() {
			return connections;
		}

		public int drainedConnections() {
			return drainedConnections;
		}

		public int closedConnections() {
			return closedConnections;
		}

		public int failedConnections() {
			return failedConnections;
		}

		public long acceptedPackets() {
			return acceptedPackets;
		}

		public long flushedPackets() {
			return flushedPackets;
		}

		public long discardedPackets() {
			return discardedPackets;
		}

		public long unconfirmedPackets() {
			return unconfirmedPackets;
		}

		public long rejectedAfterDrainPackets() {
			return rejectedAfterDrainPackets;
		}

		public long overloadDroppedPackets() {
			return overloadDroppedPackets;
		}

		public long totalDiscardedPackets() {
			return totalDiscardedPackets;
		}

		public long remainingQueuedPackets() {
			return remainingQueuedPackets;
		}

		public boolean timedOut() {
			return timedOut;
		}

		public Throwable failure() {
			return failure;
		}

		public boolean hasFailures() {
			return failedConnections > 0 || timedOut;
		}

		public String describe() {
			return "connections=" + connections + " drained=" + drainedConnections + " closed="
					+ closedConnections + " failed=" + failedConnections + " accepted=" + acceptedPackets
					+ " flushed=" + flushedPackets + " discarded=" + discardedPackets + " unconfirmed="
					+ unconfirmedPackets + " rejectedAfterDrain=" + rejectedAfterDrainPackets
					+ " overloadDropped=" + overloadDroppedPackets + " totalDiscarded="
					+ totalDiscardedPackets + " remainingQueued=" + remainingQueuedPackets + " timedOut="
					+ timedOut + (failure == null ? "" : " failure=" + failure);
		}
	}
}
