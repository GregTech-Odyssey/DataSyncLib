package com.gto.datasynclib.util;

import com.gto.datasynclib.DataSyncLib;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Task queue for one session, driven by its own daemon thread, with the shape of GregTech Modern's
 * {@code TaskHandler} ({@code com.gregtechceu.gtceu.utils.TaskHandler}, the per-level handler the
 * GTO fork drives from the level tick, here driven by a scheduled worker).
 *
 * <p>The structure that makes it cheap:</p>
 * <ul>
 *   <li><strong>Intrusive queue</strong> — a task entry <em>is</em> its queue node, so registering a
 *       task allocates one object and links it in, with no node wrapper, no array growth and no
 *       copy. {@link #enqueueTick} is O(1) and never scans the task set, so registering a hundred
 *       thousand holders is a hundred thousand links, not a quadratic number of comparisons.</li>
 *   <li><strong>Two queues</strong> — {@link #enqueueTask}/{@link #enqueueTick} only ever touch the
 *       waiting queue, under a short lock; the worker splices the whole waiting queue into its own
 *       at the start of a run (O(1) splice), so the task bodies run with no lock held and a task may
 *       enqueue another one.</li>
 *   <li><strong>O(1) removal</strong> — dropping a task is unlinking its node, which the run loop
 *       does as it passes (or the caller does by flipping the subscription), never a scan.</li>
 *   <li><strong>Subscriptions instead of keys</strong> — a caller keeps the {@link TaskSubscription}
 *       and passes it back to {@link #enqueueTick(TaskSubscription, BooleanSupplier, Runnable, int,
 *       int)}; a still-live handle is returned unchanged, so re-registering cannot duplicate the
 *       task. There is no key map and no identity lookup on the registration path.</li>
 *   <li><strong>No per-run allocation</strong> — the run loop walks the linked nodes and reuses the
 *       entries it keeps.</li>
 * </ul>
 *
 * <p>Timing: one {@link #run()} is one <em>period</em> of wall-clock time (the constructor's
 * {@code period} in milliseconds — 50 ms for the shared executors), not a game tick. {@code cycle}
 * is counted in periods: {@code 1} runs every period, {@code 40} about every two seconds. Wall-clock
 * timing keeps working while the game is paused or catching up; a task that needs game time should
 * read it from the level it works on. Registrations are staggered over their cycle by a round-robin
 * phase, so a burst of holders registering in one go does not all become due on the same period.</p>
 *
 * <p>One throwing task is logged with its stack trace and does not disturb the others, and the
 * executor itself keeps running.</p>
 */
public final class AsyncExecutor {

    private static final AtomicInteger WORKER_COUNT = new AtomicInteger(0);

    private final ScheduledExecutorService service;
    private final long period;

    /**
     * Guards {@link #waitingHead}, {@link #waitingTail}, {@link #future} and {@link #phase}; never
     * held while a task runs.
     */
    private final Object lock = new Object();

    /**
     * Registrations the worker has not taken yet; guarded by {@link #lock}.
     */
    private Entry waitingHead;
    private Entry waitingTail;

    /**
     * Registrations the worker runs; owned by the worker thread.
     */
    private Entry taskHead;
    private Entry taskTail;

    private ScheduledFuture<?> future;
    private int phase;

    /**
     * Bumped by {@link #cancel()}, read by the worker: set once the session is over so a task that
     * is already running cannot keep the session's objects alive.
     */
    private volatile boolean cancelled = true;

    private int ticks;

    public AsyncExecutor(long period) {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new ExecutorThread(r);
            thread.setName("Async-Executor-worker-" + WORKER_COUNT.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(1);
            return thread;
        }), period);
    }

    public AsyncExecutor(ScheduledExecutorService service, long period) {
        this.service = service;
        this.period = period;
    }

    /**
     * Resumes the executor; a task registered while it was paused starts running now.
     */
    public void activate() {
        synchronized (lock) {
            cancelled = false;
        }
    }

    /**
     * Ends the session: every registration is dropped (its subscription reads "not subscribed") and
     * the schedule stops, so nothing keeps referring to the objects of the session that is over.
     *
     * <p>One final run is scheduled to make the worker unlink and release the entries it holds; it
     * sees the cancellation, drops them and stops the schedule again.</p>
     */
    public void cancel() {
        synchronized (lock) {
            cancelled = true;
            var entry = waitingHead;
            while (entry != null) {
                var next = entry.next;
                entry.unsubscribe();
                entry = next;
            }
            waitingHead = null;
            waitingTail = null;
            stopLocked();
            scheduleLocked();
        }
    }

    /**
     * Runs {@code runnable} once after {@code delay} periods.
     *
     * @return the handle, or an unsubscribed one when the executor is not running
     */
    public TaskSubscription enqueueTask(Runnable runnable, int delay) {
        var entry = new Entry(runnable, null, 1, true, Math.max(0, delay));
        return enqueue(entry);
    }

    /**
     * Runs {@code runnable} every {@code cycle} periods until {@code isRemove} answers {@code true}
     * or the subscription is unsubscribed.
     *
     * @param isRemove polled when the task is due; {@code true} drops it, {@code null} means "never"
     * @param runnable the task body
     * @param cycle    how many periods to wait between runs; values below {@code 1} run every period
     * @param delay    extra periods to wait before the first run
     * @return the handle, or an unsubscribed one when the executor is not running
     */
    public TaskSubscription enqueueTick(BooleanSupplier isRemove, Runnable runnable, int cycle, int delay) {
        return enqueueTick(null, isRemove, runnable, cycle, delay);
    }

    /**
     * The form a caller that keeps its handle uses: a {@code subscription} that is still live is
     * returned unchanged, so re-registering the same task cannot stack a second copy; a dead one is
     * replaced by a fresh registration.
     *
     * @param subscription the handle from the previous registration, or {@code null}
     * @param isRemove     polled when the task is due; {@code true} drops it, {@code null} means "never"
     * @param runnable     the task body
     * @param cycle        how many periods to wait between runs; values below {@code 1} run every period
     * @param delay        extra periods to wait before the first run
     * @return the live handle: {@code subscription} itself when it is still running, the new one otherwise
     */
    public TaskSubscription enqueueTick(TaskSubscription subscription, BooleanSupplier isRemove, Runnable runnable, int cycle, int delay) {
        if (subscription != null && subscription.isSubscribed()) return subscription;
        var entry = new Entry(runnable, isRemove, Math.max(1, cycle), false, Math.max(0, delay));
        // stagger a burst of registrations over their cycle instead of making them all due at once
        if (entry.cycle > 1) entry.delay += phase++ % entry.cycle;
        return enqueue(entry);
    }

    private TaskSubscription enqueue(Entry entry) {
        synchronized (lock) {
            if (cancelled) {
                entry.unsubscribe();
                return entry;
            }
            entry.prev = waitingTail;
            if (waitingTail != null) waitingTail.next = entry;
            else waitingHead = entry;
            waitingTail = entry;
            scheduleLocked();
            return entry;
        }
    }

    private void scheduleLocked() {
        if (future != null) return;
        future = service.scheduleAtFixedRate(this::run, 0, period, TimeUnit.MILLISECONDS);
    }

    private void stopLocked() {
        var running = future;
        future = null;
        if (running != null) running.cancel(false);
    }

    private void run() {
        if (cancelled) {
            dropTasks();
            synchronized (lock) {
                stopLocked();
            }
            return;
        }
        mergeWaiting();
        var tick = ticks;
        var entry = taskHead;
        while (entry != null) {
            var next = entry.next;
            if (cancelled) {
                dropTasks();
                synchronized (lock) {
                    stopLocked();
                }
                return;
            }
            if (!entry.isSubscribed()) {
                unlinkTasks(entry);
            } else if (entry.delay > 0) {
                entry.delay--;
            } else if (tick >= entry.lastTick) {
                if (entry.isRemove != null && entry.isRemove.getAsBoolean()) {
                    entry.unsubscribe();
                    unlinkTasks(entry);
                } else {
                    try {
                        entry.runnable.run();
                    } catch (Throwable e) {
                        // logged with the throwable (not just its message): the stack trace is the
                        // only clue for a task that fails every period, and one broken task must not
                        // stop the others or kill the scheduled run
                        DataSyncLib.LOGGER.error("Async task failed", e);
                    }
                    if (entry.oneShot) {
                        entry.unsubscribe();
                        unlinkTasks(entry);
                    } else {
                        entry.lastTick = tick + entry.cycle;
                    }
                }
            }
            entry = next;
        }
        ticks++;
        synchronized (lock) {
            // nothing left to run: go back to sleep, the next enqueue restarts the schedule
            if (taskHead == null && waitingHead == null) stopLocked();
        }
    }

    /**
     * Splices the waiting queue onto the end of the worker's queue, O(1).
     */
    private void mergeWaiting() {
        synchronized (lock) {
            if (waitingHead == null) return;
            if (taskTail == null) {
                taskHead = waitingHead;
                taskTail = waitingTail;
            } else {
                taskTail.next = waitingHead;
                waitingHead.prev = taskTail;
                taskTail = waitingTail;
            }
            waitingHead = null;
            waitingTail = null;
        }
    }

    private void unlinkTasks(Entry entry) {
        if (entry.prev != null) entry.prev.next = entry.next;
        else taskHead = entry.next;
        if (entry.next != null) entry.next.prev = entry.prev;
        else taskTail = entry.prev;
        entry.prev = null;
        entry.next = null;
    }

    /**
     * Drops the worker's whole queue and releases the entries (used when the session is over).
     */
    private void dropTasks() {
        var entry = taskHead;
        taskHead = null;
        taskTail = null;
        while (entry != null) {
            var next = entry.next;
            entry.prev = null;
            entry.next = null;
            entry.unsubscribe();
            entry = next;
        }
    }

    /**
     * One registration: the entry is its own node in the queue, so linking and unlinking it never
     * allocates.
     */
    private static final class Entry extends TaskSubscription {

        private final Runnable runnable;
        private final BooleanSupplier isRemove;
        private final int cycle;
        private final boolean oneShot;

        private Entry prev;
        private Entry next;
        /**
         * Counted down before the first run (the enqueue delay), then used as the next due period.
         */
        private int delay;
        private int lastTick;

        private Entry(Runnable runnable, BooleanSupplier isRemove, int cycle, boolean oneShot, int delay) {
            this.runnable = runnable;
            this.isRemove = isRemove;
            this.cycle = cycle;
            this.oneShot = oneShot;
            this.delay = delay;
        }
    }

    public static final class ExecutorThread extends Thread {


        public ExecutorThread(Runnable runnable) {
            super(runnable);
        }

    }

}
