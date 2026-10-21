package org.spigotmc;

import com.falchus.spigot.optimizations.FastNetworkManager;
import net.minecraft.server.EnumProtocolDirection;
import net.minecraft.server.NetworkManager;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class TicksPerSecondCommandTest {

    @Test
    public void trackingMetricsIgnoreConnectionsThatAreNotReadyYet() {
        NetworkManager connection = new NetworkManager(EnumProtocolDirection.SERVERBOUND);

        long[] metrics = TicksPerSecondCommand.trackingPacketCounts(
                Arrays.asList(null, connection.fastNetworkManager, null));

        assertArrayEquals(new long[] {0L, 0L}, metrics);
    }

    @Test
    public void trackingMetricsTreatMissingCollectionAsEmpty() {
        assertArrayEquals(new long[] {0L, 0L},
                TicksPerSecondCommand.trackingPacketCounts(null));
    }

    @Test
    public void trackingManagerTreatsMissingPlayerConnectionAsEmpty() {
        assertNull(TicksPerSecondCommand.trackingManager(null));
    }
}
