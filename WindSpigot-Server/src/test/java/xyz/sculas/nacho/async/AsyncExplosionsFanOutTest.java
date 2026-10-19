package xyz.sculas.nacho.async;

import com.windpvp.windspigot.async.BoundedThreadPoolExecutor;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract for the explosion density fan-out.
 *
 * <p>Explosion.affectEntities submits one ray-trace callable per affected entity and then blocks.
 * These tests pin the guarantees that make that safe:
 * <ul>
 *   <li>every accepted job is computed on a worker, never on the caller;</li>
 *   <li>a rejected, failed or timed-out fan-out returns null so the caller goes synchronous;</li>
 *   <li>a worker of the pool can never fan out into its own pool.</li>
 * </ul>
 */
public class AsyncExplosionsFanOutTest {

    private BoundedThreadPoolExecutor pool;

    @After
    public void stopPool() {
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            pool = null;
            AsyncExplosions.EXECUTOR = null;
        }
    }

    private void startPool(int workers, int capacity) {
        pool = new BoundedThreadPoolExecutor(workers, capacity, runnable -> {
            Thread thread = new Thread(runnable, "Test Explosion Worker");
            thread.setDaemon(true);
            return thread;
        });
        AsyncExplosions.EXECUTOR = pool;
    }

    @Test
    public void acceptedJobsRunOnWorkersInOrder() {
        startPool(2, 64);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int value = i;
            jobs.add(() -> value * value);
        }
        List<Integer> results = AsyncExplosions.awaitAll(jobs, 5000L);
        assertNotNull("a healthy fan-out must produce results", results);
        assertEquals(8, results.size());
        for (int i = 0; i < 8; i++) {
            assertEquals(i * i, results.get(i).intValue());
        }
    }

    @Test
    public void failingJobAbortsTheWholeFanOut() {
        startPool(2, 64);
        List<Callable<Integer>> jobs = new ArrayList<>();
        jobs.add(() -> 1);
        jobs.add(() -> {
            throw new IllegalStateException("ray trace blew up");
        });
        jobs.add(() -> 3);
        assertNull("a worker failure must abort, never return partial results",
                AsyncExplosions.awaitAll(jobs, 5000L));
    }

    @Test
    public void missingPoolReturnsNull() {
        AsyncExplosions.EXECUTOR = null;
        List<Callable<Integer>> jobs = new ArrayList<>();
        jobs.add(() -> 1);
        assertNull(AsyncExplosions.awaitAll(jobs, 1000L));
        assertFalse(AsyncExplosions.canFanOut());
    }

    @Test
    public void emptyJobListIsHandledWithoutSubmitting() {
        startPool(1, 8);
        assertNull("no jobs means nothing to wait for",
                AsyncExplosions.awaitAll(new ArrayList<Callable<Integer>>(), 1000L));
    }

    @Test
    public void shutdownPoolRefusesWork() {
        startPool(1, 8);
        pool.shutdown();
        List<Callable<Integer>> jobs = new ArrayList<>();
        jobs.add(() -> 1);
        assertNull("a shut-down pool must never accept density work",
                AsyncExplosions.awaitAll(jobs, 1000L));
        assertFalse(AsyncExplosions.canFanOut());
    }

    @Test
    public void timeoutReturnsNullInsteadOfBlockingForever() {
        startPool(1, 8);
        List<Callable<Integer>> jobs = new ArrayList<>();
        jobs.add(() -> {
            Thread.sleep(10_000L);
            return 1;
        });
        assertNull("a job that exceeds the budget must abort the fan-out",
                AsyncExplosions.awaitAll(jobs, 50L));
    }

    @Test
    public void callerOnAWorkerCannotFanOut() throws Exception {
        startPool(1, 8);
        final List<Callable<Integer>> jobs = new ArrayList<>();
        jobs.add(() -> 1);
        // Submit a job that itself calls canFanOut from inside the worker.
        final java.util.concurrent.Future<Boolean> observed = pool.submit(
                AsyncExplosions::canFanOut);
        assertFalse("a pool worker must never fan out into its own pool", observed.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void saturatedQueueAbortsInsteadOfRunningInline() {
        startPool(1, 1);
        // Occupy the single worker and the single queue slot.
        pool.execute(() -> {
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException ignored) {
            }
        });
        List<Callable<Integer>> jobs = new ArrayList<>();
        jobs.add(() -> 1);
        jobs.add(() -> 2);
        jobs.add(() -> 3);
        assertNull("a saturated pool must abort, never execute on the caller",
                AsyncExplosions.awaitAll(jobs, 10L));
    }
}
