package nl.whitemcwizard.universalsettings.sync;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coalesces rapid settings changes into one upload: each schedule() call resets
 * a short timer, and a failed push gets a single longer retry.
 */
public class DebouncedPusher {

    private static final long DEBOUNCE_SECONDS = 10;
    private static final long RETRY_SECONDS = 60;

    private final ScheduledExecutorService executor;
    private final Runnable push;
    private ScheduledFuture<?> pending;

    public DebouncedPusher(ScheduledExecutorService executor, Runnable push) {
        this.executor = executor;
        this.push = push;
    }

    public synchronized void schedule() {
        schedule(DEBOUNCE_SECONDS);
    }

    public synchronized void scheduleRetry() {
        schedule(RETRY_SECONDS);
    }

    private synchronized void schedule(long delaySeconds) {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = executor.schedule(push, delaySeconds, TimeUnit.SECONDS);
    }

    public synchronized void cancel() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }
}
