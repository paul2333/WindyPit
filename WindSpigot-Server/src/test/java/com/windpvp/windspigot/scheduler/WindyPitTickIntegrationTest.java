
package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.windpvp.windspigot.config.WindyPitConfig;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class WindyPitTickIntegrationTest {

    @Test
    public void onTickSeamRunsGlobalControlThenOwnerCommitPump() {
        WindyPitTickIntegration it = new WindyPitTickIntegration(WindyPitConfig.defaults());
        final List<String> runs = new ArrayList<String>();
        it.globalScheduler().schedule(new Runnable() {
            @Override
            public void run() {
                runs.add("global");
            }
        });
        it.regionScheduler().schedule(it.arena(), new Runnable() {
            @Override
            public void run() {
                runs.add("arena:" + Thread.currentThread().getName());
            }
        });
        it.onTickSeam();
        assertEquals(2, runs.size());
        assertEquals("global", runs.get(0));
        assertTrue(runs.get(1).startsWith("arena:"));
        it.regionScheduler().assertRegionThread(it.arena());
        assertEquals(1, it.seamCount());
        assertEquals(0, it.pipeline().tickCount());
    }

    @Test
    public void shutdownDrainExecutesPendingWorkAndRejectsNewWorkIdempotently() throws Exception {
        WindyPitTickIntegration it = new WindyPitTickIntegration(WindyPitConfig.defaults());
        final List<Integer> ran = new ArrayList<Integer>();
        it.regionScheduler().schedule(it.arena(), new Runnable() {
            @Override
            public void run() {
                ran.add(1);
            }
        });
        it.globalScheduler().schedule(new Runnable() {
            @Override
            public void run() {
                ran.add(2);
            }
        });
        it.shutdownDrain(5000);
        assertEquals(2, ran.size());
        try {
            it.regionScheduler().schedule(it.arena(), new Runnable() {
                @Override
                public void run() {
                }
            });
            fail("expected rejection after shutdown");
        } catch (RejectedTaskException expected) {
            // expected
        }
        try {
            it.globalScheduler().schedule(new Runnable() {
                @Override
                public void run() {
                }
            });
            fail("expected rejection after shutdown");
        } catch (RejectedTaskException expected) {
            // expected
        }
        it.shutdownDrain(5000);
        assertEquals(2, ran.size());
        // seam becomes a no-op after drain
        it.onTickSeam();
        assertEquals(2, ran.size());
    }

    @Test
    public void compatModeCreatesNoWorldOwnerDomains() {
        WindyPitTickIntegration it = new WindyPitTickIntegration(WindyPitConfig.defaults());
        assertTrue("compat mode must not create world owner domains", it.worldRegions() == null);
    }

    @Test
    public void regionModeCreatesWorldOwnerRuntime() throws Exception {
        WindyPitConfig config = WindyPitConfig.parse(
                "threading:\n  mode: region\n  region-threads: 2\nfeatures:\n  async-entity-tracking: true\n");
        WindyPitTickIntegration it = new WindyPitTickIntegration(config);
        assertTrue("region mode must create the world owner runtime", it.worldRegions() != null);
        assertEquals(0, it.worldRegions().domainCount());
        it.shutdownDrain(5000);
        assertTrue(it.worldRegions().isClosed());
    }


    @Test
    public void arenaTasksRunFifoAcrossSeams() {
        WindyPitTickIntegration it = new WindyPitTickIntegration(WindyPitConfig.defaults());
        final List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < 10; i++) {
            final int v = i;
            it.regionScheduler().schedule(it.arena(), new Runnable() {
                @Override
                public void run() {
                    order.add(v);
                }
            });
        }
        it.onTickSeam();
        for (int i = 0; i < 10; i++) {
            assertEquals(Integer.valueOf(i), order.get(i));
        }
        it.onTickSeam();
        assertEquals(2, it.seamCount());
    }
}

