
package com.windpvp.windspigot.scheduler;

/**
 * Thrown when a task or message cannot be accepted because the target region queue is full
 * or the scheduler has been closed. Never silently dropped: callers see the rejection and
 * diagnostics record it.
 */
public class RejectedTaskException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RejectedTaskException(String message) {
        super(message);
    }
}

