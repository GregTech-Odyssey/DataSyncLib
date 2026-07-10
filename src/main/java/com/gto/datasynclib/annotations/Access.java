package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a non-final field to use final-like access patterns for character data processing.
 * Annotated non-final fields will be treated with the same access mode as final fields.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Access {

    /**
     * Whether to save and load instances for non-final fields.
     * When enabled, the instance will be persisted and restored during serialization/deserialization.
     *
     * @return true if instances should be saved and loaded, false otherwise
     */
    boolean createInstance() default false;
}
