
package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class NetworkSendQueueTest {

    @Test
    public void framesFlushInFifoOrder() {
        NetworkSendQueue q = new NetworkSendQueue(8, NetworkSendQueue.OverflowPolicy.DROP_OLDEST);
        q.attach(1);
        q.enqueue(1, "a");
        q.enqueue(1, "b");
        q.enqueue(1, "c");
        final List<String> out = new ArrayList<String>();
        assertEquals(3, q.drainPlayer(1, new java.util.function.Consumer<NetworkSendQueue.Frame>() {
            @Override
            public void accept(NetworkSendQueue.Frame f) {
                out.add((String) f.packet());
            }
        }));
        assertEquals(3, out.size());
        assertEquals("a", out.get(0));
        assertEquals("c", out.get(2));
        assertEquals(3, q.totalFlushed());
        assertEquals(0, q.depth(1));
    }

    @Test
    public void dropOldestKeepsFreshestFrames() {
        NetworkSendQueue q = new NetworkSendQueue(2, NetworkSendQueue.OverflowPolicy.DROP_OLDEST);
        q.attach(1);
        q.enqueue(1, "p1");
        q.enqueue(1, "p2");
        q.enqueue(1, "p3");
        assertEquals(1, q.totalDropped());
        final List<Long> seqs = new ArrayList<Long>();
        q.drainPlayer(1, new java.util.function.Consumer<NetworkSendQueue.Frame>() {
            @Override
            public void accept(NetworkSendQueue.Frame f) {
                seqs.add(f.sequence());
            }
        });
        assertEquals(2, seqs.size());
        assertEquals(2L, seqs.get(0).longValue());
        assertEquals(3L, seqs.get(1).longValue());
    }

    @Test
    public void rejectPolicySurfacesBackpressure() {
        NetworkSendQueue q = new NetworkSendQueue(1, NetworkSendQueue.OverflowPolicy.REJECT);
        q.attach(1);
        q.enqueue(1, "p1");
        try {
            q.enqueue(1, "p2");
            fail("expected RejectedTaskException in REJECT mode");
        } catch (RejectedTaskException expected) {
            // expected
        }
        assertEquals(1, q.totalDropped());
        assertEquals(1, q.drainPlayer(1, new java.util.function.Consumer<NetworkSendQueue.Frame>() {
            @Override
            public void accept(NetworkSendQueue.Frame f) {
            }
        }));
    }

    @Test
    public void detachDropsLeftoversAndUnbinds() {
        NetworkSendQueue q = new NetworkSendQueue(4, NetworkSendQueue.OverflowPolicy.DROP_OLDEST);
        q.attach(2);
        q.enqueue(2, "x");
        q.enqueue(2, "y");
        assertEquals(2, q.detach(2));
        assertEquals(2, q.totalDropped());
        assertFalse(q.isAttached(2));
        assertEquals(0, q.drainPlayer(2, new java.util.function.Consumer<NetworkSendQueue.Frame>() {
            @Override
            public void accept(NetworkSendQueue.Frame f) {
            }
        }));
        try {
            q.enqueue(2, "z");
            fail("expected IllegalStateException for detached player");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void perPlayerQueuesAreIsolated() {
        NetworkSendQueue q = new NetworkSendQueue(4, NetworkSendQueue.OverflowPolicy.DROP_OLDEST);
        q.attach(1);
        q.attach(2);
        q.enqueue(1, "a");
        assertEquals(1, q.depth(1));
        assertEquals(0, q.depth(2));
        final List<Integer> seen = new ArrayList<Integer>();
        assertEquals(1, q.drainAll(new java.util.function.Consumer<NetworkSendQueue.Frame>() {
            @Override
            public void accept(NetworkSendQueue.Frame f) {
                seen.add(f.playerId());
            }
        }));
        assertEquals(Collections.singletonList(1), seen);
        assertEquals(2, q.attachedCount());
        assertTrue(q.totalEnqueued() >= 1);
    }
}

