package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for <strong>server-to-client</strong> synchronization.
 *
 * <p>When the server calls
 * {@link com.gto.datasynclib.network.DataSyncNetwork#syncBlockEntityToClient
 * DataSyncNetwork.syncBlockEntityToClient()} (or the entity equivalent), changed fields
 * annotated with {@code @SyncToClient} are serialized and sent to all players
 * tracking the chunk/entity.</p>
 *
 * <h3>Automatic vs. manual update:</h3>
 * <ul>
 *   <li>{@code autoUpdate = true} (default) — the field is checked for changes
 *       automatically during each sync call via dirty flag detection</li>
 *   <li>{@code autoUpdate = false} — the field is only synced when explicitly
 *       marked via {@code markFieldsForSync()}</li>
 * </ul>
 *
 * <h3>Change notification:</h3>
 * <p>Set {@link #notifyUpdate()} to {@code true} to trigger
 * {@link com.gto.datasynclib.IFieldDataHolder#scheduleUpdate(com.gto.datasynclib.LogicalSide)}
 * on the client after receiving a sync update. Use {@link #listener()} for
 * field-level callback on value change.</p>
 *
 * @see SyncToServer
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SyncToClient {

    /**
     * Determines whether to trigger an update notification when the field value changes.
     * When set to true, the client will be notified of the change, which can be useful for
     * triggering UI refreshes or other client-side updates.
     *
     * @return true if an update notification should be sent when the field changes, false otherwise
     * &#064;default false
     * @see com.gto.datasynclib.IFieldDataHolder#scheduleUpdate(com.gto.datasynclib.LogicalSide)
     */
    boolean notifyUpdate() default false;

    /**
     * Determines whether the field should be automatically updated.
     * When set to true, the field will be automatically synchronized when its value changes
     * (detected via the dirty flag mechanism). When set to false, the field will only be
     * synchronized when explicitly marked as dirty.
     *
     * @return true if the field should be automatically updated, false if manual update is required
     * &#064;default true
     */
    boolean autoUpdate() default true;

    /**
     * Condition for synchronization.
     * Method signature: T -> boolean
     *
     * @return the method name for the condition check
     */
    String condition() default "";

    /**
     * Specifies a listener method to be called when this field receives a synchronization update.
     * The method must have the signature: (T newValue, T oldValue) -> void, where T is the field type.
     * This must be a non-static method accessible from this class.
     *
     * @return the name of the listener method
     */
    String listener() default "";
}
