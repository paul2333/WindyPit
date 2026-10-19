package com.windpvp.windspigot.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.Test;

/**
 * Cross-owner message contract (Prompt 70): only deeply immutable messages may cross an owner
 * boundary, delivery is FIFO on the receiving owner, and a rejected delivery is never executed on
 * the caller/primary thread.
 */
public class EntityCrossOwnerMessageTest {

    @Test
    public void immutableMessageIsDeliveredOnTargetOwnerInFifoOrder() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final OwnedRegion source = OwnedRegion.dedicated("owner-a", RegionKind.WORLD, 64);
        final OwnedRegion target = OwnedRegion.dedicated("owner-b", RegionKind.WORLD, 64);
        scheduler.register(source);
        scheduler.register(target);
        final List<Integer> delivered = Collections.synchronizedList(new ArrayList<Integer>());
        final List<String> deliveryThreads = Collections.synchronizedList(new ArrayList<String>());
        try {
            for (int i = 1; i <= 5; i++) {
                final EntityOwnerMessage message = new EntityOwnerMessage(
                        EntityOwnerMessage.Kind.MOVED, i, 3, i * 2.0D, 64.0D, 0.0D);
                scheduler.sendImmutable(target, "owner-a", message,
                        new java.util.function.Consumer<EntityOwnerMessage>() {
                            @Override
                            public void accept(EntityOwnerMessage payload) {
                                delivered.add(payload.entityId());
                                deliveryThreads.add(Thread.currentThread().getName());
                            }
                        });
            }
            await("five cross-owner deliveries", 5000, new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return delivered.size() == 5;
                }
            });
            assertEquals(Integer.valueOf(1), delivered.get(0));
            assertEquals(Integer.valueOf(5), delivered.get(4));
            for (String thread : deliveryThreads) {
                assertEquals("WindyPit-Region-owner-b", thread);
            }
        } finally {
            scheduler.shutdownDrain(5000);
        }
    }

    @Test
    public void regionMessageEnvelopeCarriesSourceAndSequence() {
        final EntityOwnerMessage payload = new EntityOwnerMessage(
                EntityOwnerMessage.Kind.TRANSFER, 9, 2, 1.0D, 2.0D, 3.0D);
        final RegionMessage<EntityOwnerMessage> message = RegionMessage.of("owner-a", 42L, payload);

        assertEquals("owner-a", message.fromRegionId());
        assertEquals(42L, message.sequence());
        assertSame(payload, message.payload());
        assertEquals("entity-TRANSFER", message.payload().messageType());
    }

    @Test
    public void mutableOrNonFinalPayloadsAreRejectedBeforeCrossingOwners() {
        expectIllegalArgument(new Runnable() {
            @Override
            public void run() {
                RegionMessage.of("owner-a", 1L, new MutableMarkerMessage());
            }
        }, "non-final message class");
        expectIllegalArgument(new Runnable() {
            @Override
            public void run() {
                RegionMessage.of("owner-a", 1L, new MutableFieldMessage());
            }
        }, "non-final message field");
        expectIllegalArgument(new Runnable() {
            @Override
            public void run() {
                OwnerMessageValidator.requireImmutable("not-an-owner-message");
            }
        }, "payload without the marker");
        expectIllegalArgument(new Runnable() {
            @Override
            public void run() {
                OwnerMessageValidator.requireImmutable(new Object());
            }
        }, "arbitrary object");
    }

    @Test
    public void rejectedOwnerDeliveryNeverRunsOnTheCaller() throws Exception {
        final RegionScheduler scheduler = new RegionScheduler();
        final OwnedRegion region = OwnedRegion.dedicated("owner-b", RegionKind.WORLD, 1);
        scheduler.register(region);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean thirdRan = new AtomicBoolean(false);
        final List<String> executedThreads = Collections.synchronizedList(new ArrayList<String>());
        try {
            scheduler.schedule(region, new Runnable() {
                @Override
                public void run() {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertTrue("owner worker must start the blocker", started.await(5, TimeUnit.SECONDS));

            scheduler.schedule(region, new Runnable() {
                @Override
                public void run() {
                    executedThreads.add(Thread.currentThread().getName());
                }
            });
            try {
                scheduler.schedule(region, new Runnable() {
                    @Override
                    public void run() {
                        thirdRan.set(true);
                    }
                });
                fail("expected RejectedTaskException when the owner queue is full");
            } catch (RejectedTaskException expected) {
                // expected: rejection is visible, never silently executed on the caller
            }
            assertFalse("rejected work must not run on the caller/primary thread", thirdRan.get());
            assertEquals(1, scheduler.diagnostics().statsFor("owner-b").rejected());

            release.countDown();
            scheduler.shutdownDrain(5000);
            assertEquals(1, executedThreads.size());
            assertTrue(executedThreads.get(0).startsWith("WindyPit-Region-owner-b"));
            assertFalse(executedThreads.get(0).equals(Thread.currentThread().getName()));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void ownerSourcesUseBoundedQueuesAndNoCallerRunsFallback() throws IOException {
        final String[] sources = {
                "RegionScheduler.java",
                "RegionThread.java",
                "ComputePool.java",
                "WorldRegionRuntime.java",
                "GlobalRegionScheduler.java",
                "EntityScheduler.java"
        };
        for (String name : sources) {
            final String source = readSchedulerSource(name);
            assertFalse(name + " must not use CallerRunsPolicy", source.contains("CallerRunsPolicy"));
            assertFalse(name + " must not use an unbounded LinkedBlockingQueue",
                    source.contains("LinkedBlockingQueue"));
            assertFalse(name + " must not use a cached thread pool", source.contains("newCachedThreadPool"));
            assertFalse(name + " must not use a fixed thread pool", source.contains("newFixedThreadPool"));
        }
        final String regionThread = readSchedulerSource("RegionThread.java");
        assertTrue("owner queue must be a fixed-capacity ArrayBlockingQueue",
                regionThread.contains("new ArrayBlockingQueue<RegionTask>(region.queueCapacity())"));
        final String regionScheduler = readSchedulerSource("RegionScheduler.java");
        assertTrue("cross-owner payload must be bounded to ImmutableOwnerMessage",
                regionScheduler.contains("T extends ImmutableOwnerMessage"));
    }

    private static void expectIllegalArgument(Runnable action, String what) {
        try {
            action.run();
            fail("expected IllegalArgumentException for " + what);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void await(String what, long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5L);
        }
        fail("timed out waiting for " + what);
    }

    private static String readSchedulerSource(String fileName) throws IOException {
        final Path relative = Paths.get("src", "main", "java", "com", "windpvp", "windspigot", "scheduler", fileName);
        Path directory = Paths.get("").toAbsolutePath();
        for (int level = 0; level < 8 && directory != null; level++) {
            final Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new AssertionError("scheduler source not found: " + relative);
    }

    /** Non-final class implementing the marker: rejected by the structural validator. */
    static class MutableMarkerMessage implements ImmutableOwnerMessage {
        @Override
        public String messageType() {
            return "mutable";
        }
    }

    /** Final class with a mutable field: rejected by the structural validator. */
    static final class MutableFieldMessage implements ImmutableOwnerMessage {
        int value;

        @Override
        public String messageType() {
            return "mutable-field";
        }
    }
}
