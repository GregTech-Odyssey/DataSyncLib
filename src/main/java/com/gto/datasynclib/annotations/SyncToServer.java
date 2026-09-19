package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for <strong>client-to-server</strong> synchronization.
 *
 * <p>When the client calls
 * {@link com.gto.datasynclib.network.DataSyncNetwork#syncBlockEntityToServer
 * DataSyncNetwork.syncBlockEntityToServer()} (or the entity equivalent), changed fields
 * annotated with {@code @SyncToServer} are serialized and sent to the server for
 * authoritative processing.</p>
 *
 * <p>This is typically used for handling user input or client-side interactions that
 * need server-side validation or processing, such as GUI configuration changes,
 * button presses, or interaction state updates.</p>
 *
 * <h3>Automatic vs. manual change detection:</h3>
 * <ul>
 *   <li>{@code autoDetect = true} (default) — the field is checked for changes
 *       automatically during each sync call via dirty flag detection</li>
 *   <li>{@code autoDetect = false} — the field is only synced when explicitly
 *       marked via {@code markFieldsForSync()}</li>
 * </ul>
 *
 * <h3>Skip predicate:</h3>
 * <p>{@link #skipWhen()} references a method with signature {@code (T) -> boolean}.
 * When the method returns {@code true}, the field is <em>skipped</em> (not synced).</p>
 *
 * @see SyncToClient
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SyncToServer {

    /**
     * Determines whether to trigger an update notification when the field value changes.
     * When set to true, the server will be notified of the change through
     * {@link com.gto.datasynclib.IFieldDataHolder#scheduleUpdate(com.gto.datasynclib.LogicalSide)},
     * which can be useful for triggering server-side processing or validation.
     *
     * @return true if an update notification should be sent when the field changes,
     * false (the default) otherwise
     * @see com.gto.datasynclib.IFieldDataHolder#scheduleUpdate(com.gto.datasynclib.LogicalSide)
     */
    boolean scheduleUpdate() default false;

    /**
     * Method that decides whether this field is skipped when synchronizing.
     * Method signature: {@code (T) -> boolean}, where {@code T} is the field's declared type.
     * Returning {@code true} means "do not sync this field in this round".
     *
     * @return the name of the skip-predicate method, or an empty string for none
     */
    String skipWhen() default "";

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
     * Specifies a listener method to be called when this field receives a synchronization update.
     * The method must have the signature: (T newValue, T oldValue) -> void, where T is the field type.
     * This must be a non-static method accessible from this class.
     *
     * @return the name of the listener method
     */
    String listener() default "";
}
