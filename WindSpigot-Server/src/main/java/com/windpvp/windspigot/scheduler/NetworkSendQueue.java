
package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-player bounded outbound send queues (W2-NETWORK). Frames are immutable packets tagged with
 * a monotonically increasing sequence. Overflow follows the configured policy: DROP_OLDEST keeps
 * the freshest frames (movement, TNT/arrow trajectories) or REJECT surfaces backpressure to the
 * caller. Nothing here mutates entities or fires Bukkit events - the network thread only drains.
 */
public final class NetworkSendQueue {

    public enum OverflowPolicy {
        DROP_OLDEST,
        REJECT
    }

    public static final class Frame {
        private final int playerId;
        private final Object packet;
        private final long sequence;

        Frame(int playerId, Object packet, long sequence) {
            this.playerId = playerId;
            this.packet = packet;
            this.sequence = sequence;
        }

        public int playerId() {
            return playerId;
        }

        public Object packet() {
            return packet;
        }

        public long sequence() {
            return sequence;
        }
    }

    private static final class PlayerQueue {
        final ArrayBlockingQueue<Frame> queue;
        long enqueued;
        long dropped;
        long flushed;

        PlayerQueue(int capacity) {
            this.queue = new ArrayBlockingQueue<Frame>(capacity);
        }
    }

    private final ConcurrentHashMap<Integer, PlayerQueue> queues = new ConcurrentHashMap<Integer, PlayerQueue>();
    private final int perPlayerCapacity;
    private final OverflowPolicy policy;
    private final AtomicLong sequences = new AtomicLong();
    private final AtomicLong totalEnqueued = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();
    private final AtomicLong totalFlushed = new AtomicLong();

    public NetworkSendQueue(int perPlayerCapacity, OverflowPolicy policy) {
        if (perPlayerCapacity <= 0) {
            throw new IllegalArgumentException("per-player capacity must be positive");
        }
        this.perPlayerCapacity = perPlayerCapacity;
        this.policy = policy;
    }

    public void attach(int playerId) {
        queues.putIfAbsent(playerId, new PlayerQueue(perPlayerCapacity));
    }

    public boolean isAttached(int playerId) {
        return queues.containsKey(playerId);
    }

    public void enqueue(int playerId, Object packet) {
        final PlayerQueue q = queues.get(playerId);
        if (q == null) {
            throw new IllegalStateException("player not attached: " + playerId);
        }
        final Frame frame = new Frame(playerId, packet, sequences.incrementAndGet());
        if (q.queue.offer(frame)) {
            q.enqueued++;
            totalEnqueued.incrementAndGet();
            return;
        }
        if (policy == OverflowPolicy.REJECT) {
            q.dropped++;
            totalDropped.incrementAndGet();
            throw new RejectedTaskException("send queue full for player " + playerId);
        }
        q.queue.poll();
        if (!q.queue.offer(frame)) {
            q.dropped++;
            totalDropped.incrementAndGet();
            throw new RejectedTaskException("send queue still full for player " + playerId);
        }
        q.dropped++;
        totalDropped.incrementAndGet();
        q.enqueued++;
        totalEnqueued.incrementAndGet();
    }

    /** Drains the player's queue in FIFO order into the sink (network worker path). */
    public int drainPlayer(int playerId, java.util.function.Consumer<Frame> sink) {
        final PlayerQueue q = queues.get(playerId);
        if (q == null) {
            return 0;
        }
        int n = 0;
        Frame f;
        while ((f = q.queue.poll()) != null) {
            sink.accept(f);
            n++;
        }
        if (n > 0) {
            q.flushed += n;
            totalFlushed.addAndGet(n);
        }
        return n;
    }

    /** Detaches a disconnected player; queued frames are dropped. @return dropped leftover count */
    public int detach(int playerId) {
        final PlayerQueue q = queues.remove(playerId);
        if (q == null) {
            return 0;
        }
        final int leftover = q.queue.size();
        if (leftover > 0) {
            totalDropped.addAndGet(leftover);
        }
        return leftover;
    }

    /** Flushes every attached queue into the sink (shutdown path). */
    public int drainAll(java.util.function.Consumer<Frame> sink) {
        int total = 0;
        for (Integer playerId : new ArrayList<Integer>(queues.keySet())) {
            total += drainPlayer(playerId, sink);
        }
        return total;
    }

    public int depth(int playerId) {
        final PlayerQueue q = queues.get(playerId);
        return q == null ? 0 : q.queue.size();
    }

    public int attachedCount() {
        return queues.size();
    }

    public long totalEnqueued() {
        return totalEnqueued.get();
    }

    public long totalDropped() {
        return totalDropped.get();
    }

    public long totalFlushed() {
        return totalFlushed.get();
    }

    public int perPlayerCapacity() {
        return perPlayerCapacity;
    }

    public OverflowPolicy policy() {
        return policy;
    }

    public List<Integer> attachedPlayers() {
        return new ArrayList<Integer>(queues.keySet());
    }
}

