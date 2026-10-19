package org.bukkit.craftbukkit.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * W1-SHUTDOWN-DRAIN: AsynchronousExecutor must stop accepting work, wait for accepted work and
 * report an explicit timeout instead of leaking its pool.
 */
public class AsynchronousExecutorCloseTest {

    @Test
    public void closeWaitsForAcceptedTasksAndRejectsNewOnes() {
        TestProvider provider = new TestProvider();
        AsynchronousExecutor<String, String, Runnable, RuntimeException> executor =
                new AsynchronousExecutor<String, String, Runnable, RuntimeException>(provider, 2);

        executor.add("a", new Runnable() {
            @Override
            public void run() {
            }
        });

        assertTrue("an idle executor must terminate inside the timeout", executor.close(5000L));
        assertTrue(executor.isClosed());
        assertFalse(executor.isShutdownTimedOut());
        assertEquals("accepted stage-1 work must have completed", 1, provider.stage1.get());

        try {
            executor.add("b", new Runnable() {
                @Override
                public void run() {
                }
            });
            fail("a closed executor must reject new work");
        } catch (RejectedExecutionException expected) {
            // expected
        }
    }

    @Test
    public void closeTimesOutAndReportsRecoverableState() throws Exception {
        TestProvider provider = new TestProvider();
        provider.gate = new CountDownLatch(1);
        AsynchronousExecutor<String, String, Runnable, RuntimeException> executor =
                new AsynchronousExecutor<String, String, Runnable, RuntimeException>(provider, 1);

        executor.add("a", new Runnable() {
            @Override
            public void run() {
            }
        });

        assertFalse("a blocked stage-1 task must surface a timeout", executor.close(150L));
        assertTrue(executor.isClosed());
        assertTrue(executor.isShutdownTimedOut());
        assertTrue(executor.pendingTaskCount() >= 0);

        provider.gate.countDown();
        assertTrue("a second await must observe the eventual termination", executor.close(5000L));
        assertFalse(executor.isShutdownTimedOut());
    }

    private static final class TestProvider
            implements AsynchronousExecutor.CallBackProvider<String, String, Runnable, RuntimeException> {
        private final AtomicInteger stage1 = new AtomicInteger();
        private volatile CountDownLatch gate;

        @Override
        public String callStage1(String parameter) throws RuntimeException {
            stage1.incrementAndGet();
            CountDownLatch currentGate = gate;
            if (currentGate != null) {
                try {
                    currentGate.await(10L, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return parameter.toUpperCase();
        }

        @Override
        public void callStage2(String parameter, String object) throws RuntimeException {
        }

        @Override
        public void callStage3(String parameter, String object, Runnable callback) throws RuntimeException {
            callback.run();
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "asynchronous-executor-close-test");
            thread.setDaemon(true);
            return thread;
        }
    }
}
