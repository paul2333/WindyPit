
package com.windpvp.windspigot.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded ring buffer of owner-thread violations. Never throws from the hot path beyond the
 * caller-requested assertion; keeps the last N violations plus a total counter so diagnostics
 * can distinguish "one glitch" from "systematic misuse".
 */
public final class ThreadViolationReporter {

    public static final class ThreadViolation {
        private final long timestampNanos;
        private final String expected;
        private final String actual;
        private final String stack;

        public ThreadViolation(long timestampNanos, String expected, String actual, String stack) {
            this.timestampNanos = timestampNanos;
            this.expected = expected;
            this.actual = actual;
            this.stack = stack;
        }

        public long timestampNanos() {
            return timestampNanos;
        }

        public String expected() {
            return expected;
        }

        public String actual() {
            return actual;
        }

        public String stack() {
            return stack;
        }
    }

    private final ThreadViolation[] ring;
    private int head;
    private int size;
    private long total;
    private final Object lock = new Object();

    public ThreadViolationReporter(int capacity) {
        this.ring = new ThreadViolation[Math.max(1, capacity)];
    }

    public void report(String expected, String actual) {
        final ThreadViolation v = new ThreadViolation(System.nanoTime(), expected, actual, captureStack());
        synchronized (lock) {
            if (size == ring.length) {
                head = (head + 1) % ring.length;
                size--;
            }
            ring[(head + size) % ring.length] = v;
            size++;
            total++;
        }
    }

    public long totalViolations() {
        synchronized (lock) {
            return total;
        }
    }

    /** Oldest-first, unmodifiable copy of the retained violations. */
    public List<ThreadViolation> snapshot() {
        final List<ThreadViolation> out = new ArrayList<ThreadViolation>(size);
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                out.add(ring[(head + i) % ring.length]);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String captureStack() {
        final StackTraceElement[] elements = Thread.currentThread().getStackTrace();
        final StringBuilder sb = new StringBuilder();
        final int max = Math.min(elements.length, 12);
        for (int i = 2; i < max; i++) {
            sb.append(elements[i]).append('\n');
        }
        return sb.toString();
    }
}

