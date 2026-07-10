package com.gto.datasynclib.listener;

import org.jetbrains.annotations.NotNull;

/**
 * Interface for objects that support sync notification listeners.
 * Provides separate hooks for the data receiver (called when sync data is received)
 * and the data sender (called when sync data is sent).
 * The setter methods return the implementing type for fluent chaining.
 */
public interface ISyncNotifiable<T, LISTENER> {

    T setReceiverListener(@NotNull LISTENER receiverListener);

    T setSenderListener(@NotNull LISTENER senderListener);
}
