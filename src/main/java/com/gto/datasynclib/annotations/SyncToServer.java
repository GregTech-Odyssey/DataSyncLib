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
 * <h3>Automatic vs. manual update:</h3>
 * <ul>
 *   <li>{@code autoUpdate = true} (default) — the field is checked for changes
 *       automatically during each sync call via dirty flag detection</li>
 *   <li>{@code autoUpdate = false} — the field is only synced when explicitly
 *       marked via {@code markFieldsForSync()}</li>
 * </ul>
 *
 * <h3>Sync condition:</h3>
 * <p>{@link #condition()} references a method with signature {@code (T) -> boolean}.
 * When the method returns {@code true}, the field is <em>skipped</em> (not synced).</p>
 *
 * @see SyncToClient
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SyncToServer {

    /**
     * Determines whether to trigger an update notification when the field value changes.
     * When set to true, the server will be notified of the change, which can be useful for
     * triggering server-side processing or validation.
     *
     * @return true if an update notification should be sent when the field changes, false otherwise
     * &#064;default false
     * @see com.gto.datasynclib.IFieldDataHolder#scheduleUpdate(com.gto.datasynclib.LogicalSide)
     */
    boolean notifyUpdate() default false;

    /**
     * Condition for synchronization.
     * Method signature: T -> boolean
     *
     * @return the method name for the condition check
     */
    String condition() default "";

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
     * Specifies a listener method to be called when this field receives a synchronization update.
     * The method must have the signature: (T newValue, T oldValue) -> void, where T is the field type.
     * This must be a non-static method accessible from this class.
     *
     * @return the name of the listener method
     */
    String listener() default "";
}
