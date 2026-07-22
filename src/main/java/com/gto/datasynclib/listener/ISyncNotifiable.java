package com.gto.datasynclib.listener;

import org.jetbrains.annotations.NotNull;

/**
 * Interface for notifiable holder objects that support listener callbacks on
 * sync send and receive events.
 *
 * <h3>Two listener hooks:</h3>
 * <ul>
 *   <li><strong>Receiver listener</strong> — fired on the side that <em>receives</em>
 *       the sync data (e.g., on the client when a {@code @SyncToClient} field arrives).
 *       Called AFTER the new value has been set.</li>
 *   <li><strong>Sender listener</strong> — fired on the side that <em>sends</em> the
 *       sync data (e.g., on the server just before a {@code @SyncToClient} field is
 *       transmitted). Called AFTER the value has been written to the buffer.</li>
 * </ul>
 *
 * <h3>Fluent API:</h3>
 * <p>Setter methods return the implementing type ({@code T}) for method chaining:</p>
 * <pre>{@code
 * ObjNotifiableHolder<String> holder = ObjNotifiableHolder.create(DataSyncCodec.STRING_CODEC)
 *     .setReceiverListener((side, oldVal, newVal) -> updateUI(newVal))
 *     .setSenderListener((side, oldVal, newVal) -> logChange(newVal));
 * }</pre>
 *
 * @param <T>       the implementing type (returned by setters for chaining)
 * @param <LISTENER> the functional listener type (e.g., {@link ObjSyncListener})
 *
 * @see com.gto.datasynclib.listener.ObjNotifiableHolder
 * @see com.gto.datasynclib.listener.IntNotifiableHolder
 */
public interface ISyncNotifiable<T, LISTENER> {

    /**
     * Sets the listener to be called when sync data is <strong>received</strong>
     * (i.e., on the receiving end of a sync operation).
     *
     * @param receiverListener the listener (must not be null, use {@code *SyncListener.EMPTY} for no-op)
     * @return {@code this} for fluent chaining
     */
    T setReceiverListener(@NotNull LISTENER receiverListener);

    /**
     * Sets the listener to be called when sync data is about to be <strong>sent</strong>
     * (i.e., on the sending end of a sync operation).
     *
     * @param senderListener the listener (must not be null, use {@code *SyncListener.EMPTY} for no-op)
     * @return {@code this} for fluent chaining
     */
    T setSenderListener(@NotNull LISTENER senderListener);
}
