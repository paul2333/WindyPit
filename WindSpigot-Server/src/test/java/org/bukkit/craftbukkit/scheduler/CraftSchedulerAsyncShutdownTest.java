package org.bukkit.craftbukkit.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;
import org.junit.Test;

/**
 * W1-SHUTDOWN-DRAIN: CraftScheduler owns a bounded async pool that must be shut down explicitly.
 * These tests use a real CraftScheduler instance with a mocked plugin only - no server is started.
 */
public class CraftSchedulerAsyncShutdownTest {

    @Test
    public void shutdownDrainsAcceptedAsyncTasksBeforeReturning() throws Exception {
        CraftScheduler scheduler = new CraftScheduler();
        Plugin plugin = enabledPlugin();
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger completed = new AtomicInteger();

        scheduler.runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                firstStarted.countDown();
                await(release);
                completed.incrementAndGet();
            }
        });
        scheduler.runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                completed.incrementAndGet();
            }
        });
        scheduler.mainThreadHeartbeat(1);
        assertTrue("async task did not start", firstStarted.await(5, TimeUnit.SECONDS));

        Thread releaser = new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(100L);
                release.countDown();
            }
        }, "craft-scheduler-shutdown-releaser");
        releaser.setDaemon(true);
        releaser.start();

        assertTrue("accepted async tasks must finish inside the drain timeout",
                scheduler.shutdownAsyncExecutor(5000L));
        assertEquals("both accepted async tasks must have run", 2, completed.get());
        assertTrue(scheduler.isAsyncExecutorShutdown());
        assertFalse(scheduler.asyncExecutorShutdownTimedOut());
        assertEquals(0, scheduler.pendingAsyncTaskCount());
    }

    @Test
    public void shutdownTimesOutAndRecordsExplicitRecoverableState() throws Exception {
        CraftScheduler scheduler = new CraftScheduler();
        Plugin plugin = enabledPlugin();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        scheduler.runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                started.countDown();
                await(release);
            }
        });
        scheduler.mainThreadHeartbeat(1);
        assertTrue("blocking async task did not start", started.await(5, TimeUnit.SECONDS));

        assertFalse("a task that never finishes must surface a timeout", scheduler.shutdownAsyncExecutor(150L));
        assertTrue(scheduler.isAsyncExecutorShutdown());
        assertTrue("timeout must stay visible to the shutdown report", scheduler.asyncExecutorShutdownTimedOut());

        release.countDown();
        assertTrue("a second await must observe the eventual termination",
                scheduler.shutdownAsyncExecutor(5000L));
        assertFalse(scheduler.asyncExecutorShutdownTimedOut());
    }

    @Test
    public void shutdownWithoutTasksSucceedsAndIsIdempotent() {
        CraftScheduler scheduler = new CraftScheduler();
        assertTrue(scheduler.shutdownAsyncExecutor(1000L));
        assertTrue(scheduler.shutdownAsyncExecutor(1000L));
        assertTrue(scheduler.isAsyncExecutorShutdown());
        assertFalse(scheduler.asyncExecutorShutdownTimedOut());
    }

    @Test
    public void asyncTaskScheduledAfterShutdownIsRejectedWithoutRunning() {
        CraftScheduler scheduler = new CraftScheduler();
        assertTrue(scheduler.shutdownAsyncExecutor(1000L));
        Plugin plugin = enabledPlugin();
        final AtomicInteger runs = new AtomicInteger();

        scheduler.runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                runs.incrementAndGet();
            }
        });
        scheduler.mainThreadHeartbeat(1);

        assertEquals("plugin code must never run after the async pool is shut down", 0, runs.get());
    }

    private static Plugin enabledPlugin() {
        return (Plugin) Proxy.newProxyInstance(CraftSchedulerAsyncShutdownTest.class.getClassLoader(),
                new Class<?>[] { Plugin.class }, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        final String name = method.getName();
                        if ("isEnabled".equals(name)) {
                            return Boolean.TRUE;
                        }
                        if ("getLogger".equals(name)) {
                            return Logger.getLogger("CraftSchedulerAsyncShutdownTest");
                        }
                        if ("toString".equals(name)) {
                            return "CraftSchedulerAsyncShutdownTestPlugin";
                        }
                        if ("hashCode".equals(name)) {
                            return Integer.valueOf(System.identityHashCode(proxy));
                        }
                        if ("equals".equals(name)) {
                            return Boolean.valueOf(proxy == args[0]);
                        }
                        final Class<?> returnType = method.getReturnType();
                        if (returnType == boolean.class) {
                            return Boolean.FALSE;
                        }
                        if (returnType == int.class) {
                            return Integer.valueOf(0);
                        }
                        if (returnType == long.class) {
                            return Long.valueOf(0L);
                        }
                        return null;
                    }
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
