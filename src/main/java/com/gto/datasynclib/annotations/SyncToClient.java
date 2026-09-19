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
 * <h3>Automatic vs. manual change detection:</h3>
 * <ul>
 *   <li>{@code autoDetect = true} (default) — the field is checked for changes
 *       automatically during each sync call via dirty flag detection</li>
 *   <li>{@code autoDetect = false} — the field is only synced when explicitly
 *       marked via {@code markFieldsForSync()}</li>
 * </ul>
 *
 * <h3>Change notification:</h3>
 * <p>Set {@link #scheduleUpdate()} to {@code true} to trigger
 * {@link com.gto.datasynclib.IFieldDataHolder#scheduleUpdate(com.gto.datasynclib.LogicalSide)}
 * on the client after receiving a sync update. Use {@link #listener()} for
 * field-level callback on value change.</p>
 *
 * <h3>Skip predicate:</h3>
 * <p>{@link #skipWhen()} references a method with signature {@code (T) -> boolean}.
 * When the method returns {@code true}, the field is <em>skipped</em> (not synced).</p>
 *
 * @see SyncToServer
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SyncToClient {

    /**
     * Determines whether to trigger an update notification when the field value changes.
     * When set to true, the client will be notified of the change through
     * {@link com.gto.datasynclib.IFieldDataHolder#scheduleUpdate(com.gto.datasynclib.LogicalSide)},
     * which can be useful for triggering UI refreshes or other client-side updates.
     *
     * @return true if an update notification should be sent when the field changes,
     * false (the default) otherwise
     * @see com.gto.datasynclib.IFieldDataHolder#scheduleUpdate(com.gto.datasynclib.LogicalSide)
     */
    boolean scheduleUpdate() default false;

    /**
     * Determines whether the field's changes are detected automatically.
     * When set to true (the default), the field is compared against its previous value on every
     * {@code updateFieldDirtyFlags()} call and synchronized when it changed. When set to false,
     * no automatic change detection happens at all — the field is only sent after an explicit
     * {@code markFieldsForSync()}.
     *
     * @return true if changes to this field are detected automatically, false if the field
     * must be marked manually
     */
    boolean autoDetect() default true;

    /**
     * Method that decides whether this field is skipped when synchronizing.
     * Method signature: {@code (T) -> boolean}, where {@code T} is the field's declared type.
     * Returning {@code true} means "do not sync this field in this round".
     *
     * @return the name of the skip-predicate method, or an empty string for none
     */
    String skipWhen() default "";

    /**
     * Specifies a listener method to be called when this field receives a synchronization update.
     * The method must have the signature: (T newValue, T oldValue) -> void, where T is the field type.
     * This must be a non-static method accessible from this class.
     *
     * @return the name of the listener method
     */
    String listener() default "";
}
