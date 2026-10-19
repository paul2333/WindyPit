
package com.windpvp.windspigot.scheduler;

/**
 * How a region's owner executes tasks.
 *
 * PUMPED_PRIMARY: the primary server thread drains the region queue at explicit pump points
 * (compat mode; arena-owner=primary).
 *
 * DEDICATED_THREAD: the region owns one fixed worker thread with a bounded queue; strictly FIFO.
 */
public enum ExecutionMode {
    PUMPED_PRIMARY,
    DEDICATED_THREAD
}

