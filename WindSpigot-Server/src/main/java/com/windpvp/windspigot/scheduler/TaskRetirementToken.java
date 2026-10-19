
package com.windpvp.windspigot.scheduler;

/**
 * Retirement handle for a scheduled unit of work. Once retired the task body never executes;
 * the scheduler records the skip in diagnostics. Completed marks a body that already ran, so
 * lifecycle code (entity removal, world unload) can retire exactly the pending work.
 */
public final class TaskRetirementToken {

    private volatile boolean retired;
    private volatile boolean completed;

    public static TaskRetirementToken create() {
        return new TaskRetirementToken();
    }

    public void retire() {
        retired = true;
    }

    public boolean isRetired() {
        return retired;
    }

    void markCompleted() {
        completed = true;
    }

    public boolean isCompleted() {
        return completed;
    }
}

