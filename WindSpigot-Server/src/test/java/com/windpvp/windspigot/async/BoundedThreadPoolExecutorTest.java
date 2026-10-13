package com.windpvp.windspigot.async;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BoundedThreadPoolExecutorTest {

    @Test
    public void boundsQueueAndRejectsOverflowWithoutRunningOnSubmittingThread() throws Exception {
        BoundedThreadPoolExecutor executor = new BoundedThreadPoolExecutor(1, 1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "bounded-executor-test");
                thread.setDaemon(true);
                return thread;
            }
        });
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<Thread> overflowThread = new AtomicReference<Thread>();

        try {
            executor.execute(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue("worker should start", workerStarted.await(5, TimeUnit.SECONDS));
            executor.execute(() -> { });

            boolean rejected = false;
            try {
                executor.execute(() -> overflowThread.set(Thread.currentThread()));
            } catch (RejectedExecutionException expected) {
                rejected = true;
            }

            assertTrue("saturated execute should reject", rejected);
            assertNull("overflow must never run on the submitting thread", overflowThread.get());
            BoundedThreadPoolExecutor.Metrics metrics = executor.snapshot();
            assertEquals(1, metrics.getQueueCapacity());
            assertEquals(1, metrics.getQueuedTasks());
            assertEquals(1L, metrics.getSaturationEvents());
            assertEquals(1L, metrics.getRejectedTasks());
            assertEquals(2L, metrics.getSubmittedTasks());
            assertEquals(0L, metrics.getCompletedTasks());
        } finally {
            releaseWorker.countDown();
            executor.shutdown();
            assertTrue("executor should terminate", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnboundedQueueConfiguration() {
        new BoundedThreadPoolExecutor(1, 0, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                return new Thread(runnable);
            }
        });
    }

    @Test
    public void tryExecuteRejectsSaturatedWorkWithoutRunningOnCaller() throws Exception {
        BoundedThreadPoolExecutor executor = new BoundedThreadPoolExecutor(1, 1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "bounded-executor-reject-test");
                thread.setDaemon(true);
                return thread;
            }
        });
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<Thread> rejectedThread = new AtomicReference<Thread>();
        try {
            executor.execute(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            executor.execute(() -> { });

            boolean accepted = executor.tryExecute(() -> rejectedThread.set(Thread.currentThread()));

            assertTrue("saturated work must be rejected", !accepted);
            assertEquals(null, rejectedThread.get());
            assertEquals(1L, executor.snapshot().getRejectedTasks());
        } finally {
            releaseWorker.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void tryExecuteMetricsCountAcceptedWorkOnce() throws Exception {
        BoundedThreadPoolExecutor executor = new BoundedThreadPoolExecutor(1, 1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "bounded-executor-metrics-test");
                thread.setDaemon(true);
                return thread;
            }
        });
        CountDownLatch completed = new CountDownLatch(1);
        try {
            assertTrue("work should be accepted", executor.tryExecute(completed::countDown));
            assertTrue("work should complete", completed.await(5, TimeUnit.SECONDS));

            BoundedThreadPoolExecutor.Metrics metrics = executor.snapshot();

            assertEquals(1L, metrics.getSubmittedTasks());
            assertEquals(1L, metrics.getCompletedTasks());
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void shutdownRejectionDoesNotCountAsQueueSaturation() {
        BoundedThreadPoolExecutor executor = new BoundedThreadPoolExecutor(1, 1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "bounded-executor-shutdown-test");
                thread.setDaemon(true);
                return thread;
            }
        });
        executor.shutdown();

        try {
            boolean rejected = false;
            try {
                executor.execute(() -> { });
            } catch (RejectedExecutionException expected) {
                rejected = true;
            }

            assertTrue("shutdown executor should reject new work", rejected);
            assertEquals(0L, executor.snapshot().getSaturationEvents());
            assertEquals(1L, executor.snapshot().getRejectedTasks());
        } finally {
            assertTrue("executor should terminate", awaitTermination(executor));
        }
    }

    private static boolean awaitTermination(BoundedThreadPoolExecutor executor) {
        try {
            return executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
