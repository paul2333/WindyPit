
package com.windpvp.windspigot.scheduler;

/**
 * WindyPit tick lifecycle: five ordered phases on the primary thread.
 *
 * SERVER_CONTROL  - console, commands, global scheduler tick (non-region world work)
 * REGION_SNAPSHOT - build immutable snapshots of region state for out-of-thread consumers
 * ASYNC_COMPUTE   - submit/collect bounded pure-compute work against those snapshots
 * OWNER_COMMIT    - pump region owners so entity/world/inventory mutations run on owners
 * SHUTDOWN_DRAIN  - final drain when a shutdown has been requested
 *
 * Contract: each phase body runs exactly once per tick (or per segment), phase timing is always
 * stopped even when the body throws, and the exception is rethrown to the caller unswallowed.
 */
public final class TickPipeline {

    public enum Phase {
        SERVER_CONTROL,
        REGION_SNAPSHOT,
        ASYNC_COMPUTE,
        OWNER_COMMIT,
        SHUTDOWN_DRAIN
    }

    /** Server-provided phase bodies; all invoked on the primary thread by this pipeline. */
    public interface Target {
        void serverControl();

        /** Builds the immutable region snapshot handed to asyncCompute; may return null. */
        Object regionSnapshot();

        void asyncCompute(Object snapshot);

        void ownerCommit();
    }

    /** Timing hooks; onPhaseEnd is guaranteed even when a phase body throws. */
    public interface PhaseListener {
        void onPhaseStart(Phase phase);

        void onPhaseEnd(Phase phase, long durationNanos, boolean succeeded);
    }

    private static final Phase[] FULL_TICK = {
        Phase.SERVER_CONTROL, Phase.REGION_SNAPSHOT, Phase.ASYNC_COMPUTE, Phase.OWNER_COMMIT
    };
    private static final Phase[] DRAIN_TICK = { Phase.SERVER_CONTROL, Phase.SHUTDOWN_DRAIN };

    private final Target target;
    private volatile PhaseListener listener;
    private volatile boolean drainRequested;
    private long tickCount;
    private long segmentCount;
    private Object lastSnapshot;

    public TickPipeline(Target target) {
        if (target == null) {
            throw new IllegalArgumentException("tick target required");
        }
        this.target = target;
    }

    public void setPhaseListener(PhaseListener listener) {
        this.listener = listener;
    }

    public long tickCount() {
        return tickCount;
    }

    /**
     * Number of {@link #runSegment} invocations. The production host splits the pipeline around
     * the NMS world tick, so this is the live "pipeline ran this tick" counter.
     */
    public long segmentCount() {
        return segmentCount;
    }

    /**
     * Number of {@link #runSegment} invocations. The production host splits the pipeline around
     * the NMS world tick, so this is the live "pipeline ran this tick" counter.
     */

    /** Requests that the next tick runs SERVER_CONTROL + SHUTDOWN_DRAIN only. */
    public void requestShutdownDrain() {
        drainRequested = true;
    }

    public boolean isDrainRequested() {
        return drainRequested;
    }

    /** One full server tick through the pipeline. Exceptions close timing and rethrow. */
    public void tick() {
        tickCount++;
        if (drainRequested) {
            runPhases(DRAIN_TICK);
        } else {
            runPhases(FULL_TICK);
        }
    }

    /**
     * Runs one contiguous phase range [first..last] once, without counting a full tick. Used by
     * host integrations that split the pipeline around existing engine code (e.g. the NMS world
     * tick on the primary thread).
     */
    public void runSegment(Phase first, Phase last) {
        if (first == null || last == null) {
            throw new IllegalArgumentException("segment phases required");
        }
        if (first.ordinal() > last.ordinal()) {
            throw new IllegalArgumentException("segment first phase " + first + " is after last phase " + last);
        }
        segmentCount++;
        final Phase[] all = Phase.values();
        final Phase[] segment = new Phase[last.ordinal() - first.ordinal() + 1];
        for (int i = 0; i < segment.length; i++) {
            segment[i] = all[first.ordinal() + i];
        }
        runPhases(segment);
    }

    private void runPhases(final Phase[] phases) {
        for (int i = 0; i < phases.length; i++) {
            final Phase phase = phases[i];
            runPhase(phase, new Runnable() {
                @Override
                public void run() {
                    runPhaseBody(phase);
                }
            });
        }
    }

    private void runPhaseBody(Phase phase) {
        switch (phase) {
            case SERVER_CONTROL:
                target.serverControl();
                break;
            case REGION_SNAPSHOT:
                lastSnapshot = target.regionSnapshot();
                break;
            case ASYNC_COMPUTE:
                target.asyncCompute(lastSnapshot);
                break;
            case OWNER_COMMIT:
            case SHUTDOWN_DRAIN:
                target.ownerCommit();
                break;
            default:
                throw new IllegalStateException("unhandled phase " + phase);
        }
    }

    private void runPhase(Phase phase, Runnable body) {
        final PhaseListener l = listener;
        final long t0 = System.nanoTime();
        if (l != null) {
            l.onPhaseStart(phase);
        }
        boolean ok = false;
        try {
            body.run();
            ok = true;
        } finally {
            if (l != null) {
                l.onPhaseEnd(phase, System.nanoTime() - t0, ok);
            }
        }
    }
}

