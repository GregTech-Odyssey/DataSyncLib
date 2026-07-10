package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field whose class contains fields annotated with {@link AddToManager}.
 * When applied to a field, all fields within that field's class that are annotated with
 * {@link AddToManager} will be automatically registered with the current class's
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AdditionalHolder {

}
