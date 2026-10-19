package com.falchus.spigot.optimizations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.windpvp.windspigot.config.WindSpigotConfig;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.server.EnumProtocolDirection;
import net.minecraft.server.NetworkManager;
import net.minecraft.server.PacketPlayOutEntity;

/**
 * W1-SHUTDOWN-DRAIN: FastNetworkManager must flush every already-accepted tracking frame before a
 * connection is closed, and must surface rejected/discarded counts instead of silently swallowing
 * them.
 */
public class FastNetworkManagerDrainTest {

    private int previousThreads;
    private int previousCapacity;

    @Before
    public void configureQueues() {
        previousThreads = WindSpigotConfig.trackingThreads;
        previousCapacity = WindSpigotConfig.trackingQueueCapacity;
        WindSpigotConfig.trackingThreads = 2;
        WindSpigotConfig.trackingQueueCapacity = 64;
    }

    @After
    public void restoreQueues() {
        WindSpigotConfig.trackingThreads = previousThreads;
        WindSpigotConfig.trackingQueueCapacity = previousCapacity;
    }

    @Test
    public void drainAllFlushesEveryAcceptedFrameWithASingleFlush() {
        CountingChannel channel = new CountingChannel();
        try {
            NetworkManager connection = connection(channel);
            FastNetworkManager queue = connection.fastNetworkManager;
            for (int index = 0; index < 5; index++) {
                queue.queuePacket(new PacketPlayOutEntity(index), index % 2);
            }
            assertEquals(5, queue.queuedPacketCount());
            int flushesBeforeDrain = channel.flushCount;

            FastNetworkManager.DrainStats stats = queue.drainAll();

            assertEquals(1, stats.connections());
            assertEquals(1, stats.drainedConnections());
            assertEquals(0, stats.closedConnections());
            assertEquals(5, stats.acceptedPackets());
            assertEquals(5, stats.flushedPackets());
            assertEquals(0, stats.discardedPackets());
            assertEquals(0, stats.failedConnections());
            assertFalse(stats.timedOut());
            assertFalse(stats.hasFailures());
            assertEquals("drain must issue exactly one flush", flushesBeforeDrain + 1, channel.flushCount);
            assertEquals(0, queue.queuedPacketCount());
            assertEquals(5, channel.outboundMessages().size());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void drainAllCountsFramesOfClosedConnectionAsDiscardedAndKeepsGoing() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            NetworkManager connection = connection(channel);
            FastNetworkManager queue = connection.fastNetworkManager;
            queue.queuePacket(new PacketPlayOutEntity(1), 0);
            queue.queuePacket(new PacketPlayOutEntity(2), 0);
            channel.close().syncUninterruptibly();

            FastNetworkManager.DrainStats stats = queue.drainAll();

            assertEquals(1, stats.connections());
            assertEquals(0, stats.drainedConnections());
            assertEquals("a closed connection must be counted, not silently skipped", 1, stats.closedConnections());
            assertEquals(2, stats.acceptedPackets());
            assertEquals(0, stats.flushedPackets());
            assertEquals(2, stats.discardedPackets());
            assertEquals(2, queue.discardedPacketCount());
            assertEquals(0, queue.queuedPacketCount());
            assertTrue(stats.describe().contains("discarded=2"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void drainAllRejectsFramesArrivingAfterTheDrainAndExposesTheCount() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            NetworkManager connection = connection(channel);
            FastNetworkManager queue = connection.fastNetworkManager;
            queue.queuePacket(new PacketPlayOutEntity(1), 0);
            queue.drainAll();

            queue.queuePacket(new PacketPlayOutEntity(2), 0);
            queue.queuePacket(new PacketPlayOutEntity(3), 0);

            assertEquals("post-drain tracker frames must be rejected", 2, queue.rejectedPacketCount());
            FastNetworkManager.DrainStats second = queue.drainAll();
            assertEquals(2, second.rejectedAfterDrainPackets());
            assertEquals(0, second.acceptedPackets());
            assertEquals(0, queue.queuedPacketCount());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void aggregateDrainVisitsEveryConnectionEvenWhenOneIsClosed() {
        EmbeddedChannel active = new EmbeddedChannel();
        EmbeddedChannel closed = new EmbeddedChannel();
        try {
            NetworkManager activeConnection = connection(active);
            NetworkManager closedConnection = connection(closed);
            activeConnection.fastNetworkManager.queuePacket(new PacketPlayOutEntity(1), 0);
            closedConnection.fastNetworkManager.queuePacket(new PacketPlayOutEntity(2), 0);
            closed.close().syncUninterruptibly();

            FastNetworkManager.DrainStats stats = FastNetworkManager
                    .drainAll(Arrays.asList(activeConnection, closedConnection));

            assertEquals(2, stats.connections());
            assertEquals(1, stats.drainedConnections());
            assertEquals(1, stats.closedConnections());
            assertEquals(1, stats.flushedPackets());
            assertEquals(1, stats.discardedPackets());
            assertEquals(1, active.outboundMessages().size());
            assertEquals(0, closedConnection.fastNetworkManager.queuedPacketCount());
        } finally {
            active.finishAndReleaseAll();
            closed.finishAndReleaseAll();
        }
    }

    private static NetworkManager connection(EmbeddedChannel channel) {
        NetworkManager connection = new NetworkManager(EnumProtocolDirection.SERVERBOUND);
        connection.channel = channel;
        return connection;
    }

    private static final class CountingChannel extends EmbeddedChannel {
        private int flushCount;

        @Override
        public Channel flush() {
            flushCount++;
            return super.flush();
        }
    }
}
