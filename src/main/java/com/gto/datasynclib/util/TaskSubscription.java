package com.gto.datasynclib.util;

/**
 * Handle of one task registered on an {@link AsyncExecutor} — the library's counterpart of GregTech
 * Modern's {@code TickableSubscription}, and the reason a task never has to be looked up by key.
 *
 * <p>{@link #unsubscribe()} is O(1) and safe from any thread: it only flips a flag, and the
 * executor drops the entry on its next pass. A caller keeps the handle (see
 * {@link AsyncExecutor#enqueueTick(TaskSubscription, java.util.function.BooleanSupplier, Runnable,
 * int, int)}) so re-registering the same task does not stack a second copy.</p>
 */
public class TaskSubscription {

    private volatile boolean subscribed = true;

    TaskSubscription() {
    }

    /**
     * @return {@code true} while the executor still runs this task
     */
    public final boolean isSubscribed() {
        return subscribed;
    }

    /**
     * Stops the task: the executor skips it on the next pass and releases it. Safe to call twice
     * and from any thread.
     */
    public void unsubscribe() {
        subscribed = false;
    }
}
