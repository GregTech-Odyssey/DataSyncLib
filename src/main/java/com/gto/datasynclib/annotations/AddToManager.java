package com.gto.datasynclib.annotations;

import com.gto.datasynclib.FieldDataManager;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds this field to the {@link FieldDataManager} for management.
 * Annotations such as {@link SaveToDisk}, {@link SyncToClient}, and {@link SyncToServer}
 * extend this annotation and inherit its functionality.
 *
 * @see SaveToDisk
 * @see SyncToClient
 * @see SyncToServer
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AddToManager {

}
