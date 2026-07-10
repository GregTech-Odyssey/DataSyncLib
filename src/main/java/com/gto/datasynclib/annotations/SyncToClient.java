package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Extended from {@link AddToManager}
 * Marks a field to be synchronized to the client.
 * Fields annotated with this annotation will have their values sent from the server to the client.
 * This is typically used for displaying data on the client side, such as GUI elements or rendering states.
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
     * conditions for synchronized
     * Method signature: T -> boolean
     *
     * @return the method name for conditions
     */
    String conditions() default "";

    /**
     * Specifies a listener method to be called when this field receives a synchronization update.
     * The method must have the signature: (T newValue, T oldValue) -> void, where T is the field type.
     * This must be a non-static method accessible from this class.
     *
     * @return the name of the listener method
     */
    String listener() default "";
}
