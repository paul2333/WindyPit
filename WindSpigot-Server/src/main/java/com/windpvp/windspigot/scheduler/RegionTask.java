
package com.windpvp.windspigot.scheduler;

/**
 * One unit of region work: strictly ordered inside its region, carrying sequence, region id and
 * the region generation it was scheduled against so stale work can be identified downstream.
 */
public final class RegionTask {

    private final long sequence;
    private final String regionId;
    private final long generation;
    private final Runnable body;
    private final TaskRetirementToken token;

    public RegionTask(long sequence, String regionId, long generation, Runnable body, TaskRetirementToken token) {
        this.sequence = sequence;
        this.regionId = regionId;
        this.generation = generation;
        this.body = body;
        this.token = token;
    }

    public long sequence() {
        return sequence;
    }

    public String regionId() {
        return regionId;
    }

    public long generation() {
        return generation;
    }

    public TaskRetirementToken token() {
        return token;
    }

    /** @return true if the body ran, false when the task was retired before execution. */
    public boolean execute() {
        if (token != null && token.isRetired()) {
            return false;
        }
        body.run();
        if (token != null) {
            token.markCompleted();
        }
        return true;
    }
}

