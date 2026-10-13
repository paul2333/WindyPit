package com.falchus.spigot.optimizations;

import com.windpvp.windspigot.config.WindSpigotConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.server.EnumProtocolDirection;
import net.minecraft.server.NetworkManager;
import net.minecraft.server.PacketPlayOutEntity;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FastNetworkManagerTest {
    @Test
    public void saturationDisconnectsInsteadOfContinuingWithLostEntityUpdates() {
        int previousThreads = WindSpigotConfig.trackingThreads;
        int previousCapacity = WindSpigotConfig.trackingQueueCapacity;
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            WindSpigotConfig.trackingThreads = 1;
            WindSpigotConfig.trackingQueueCapacity = 64;
            NetworkManager connection = new NetworkManager(EnumProtocolDirection.SERVERBOUND);
            connection.channel = channel;
            FastNetworkManager queue = connection.fastNetworkManager;
            for (int index = 0; index < 64; index++) {
                queue.queuePacket(new PacketPlayOutEntity(index), 0);
            }
            assertTrue("A full queue must still accept its last in-bounds update", channel.isOpen());
            assertEquals(64, queue.queuedPacketCount());

            queue.queuePacket(new PacketPlayOutEntity(64), 0);
            channel.runPendingTasks();

            assertFalse("Lost tracker updates require disconnecting the desynchronized client", channel.isOpen());
            assertEquals("Disconnect must release retained tracker updates", 0, queue.queuedPacketCount());
            assertEquals(1L, queue.droppedPacketCount());
            for (int index = 0; index < 128; index++) {
                queue.queuePacket(new PacketPlayOutEntity(index), 0);
            }
            assertEquals("A closed connection must not retain later tracker updates", 0, queue.queuedPacketCount());
        } finally {
            channel.finishAndReleaseAll();
            WindSpigotConfig.trackingThreads = previousThreads;
            WindSpigotConfig.trackingQueueCapacity = previousCapacity;
        }
    }
}
