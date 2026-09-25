package com.gto.datasynclib;

/**
 * Marks the current thread as writing a non-consuming full snapshot.
 *
 * <p>1.21.1/NeoForge addition (same contract as MachineLib's adaptation):
 * {@link FieldDataManager#writeSnapshot} serves exactly one newly-added observer, so it must
 * not clear the per-field dirty flags — the delta baseline of the already-subscribed observers
 * has to survive. {@link FieldDataManager#writeToNetworkBuffer} checks {@link #active()}
 * before clearing them.</p>
 *
 * <p>Scopes nest: {@link #close()} restores the previous state.</p>
 */
public final class SnapshotScope implements AutoCloseable {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private final Boolean previous = ACTIVE.get();

    public SnapshotScope() {
        ACTIVE.set(Boolean.TRUE);
    }

    /**
     * Checks whether the current thread is writing a full snapshot.
     *
     * @return {@code true} while a {@link SnapshotScope} is open on this thread
     */
    public static boolean active() {
        return ACTIVE.get() == Boolean.TRUE;
    }

    @Override
    public void close() {
        if (previous == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(previous);
        }
    }
}
