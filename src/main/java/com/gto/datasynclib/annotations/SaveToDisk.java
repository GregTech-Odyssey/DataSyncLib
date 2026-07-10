package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Extended from {@link AddToManager}
 * Marks a field to be persisted to disk.
 * Annotated fields will be automatically saved and loaded.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SaveToDisk {

    /**
     * Custom key name for storage.
     * If empty, the field name will be used as the key.
     *
     * @return custom storage key name
     */
    String key() default "";

    /**
     * Condition for persisting to disk.
     * Method signature: T -> boolean
     *
     * @return the method name for the condition check
     */
    String condition() default "";

    /**
     * Whether to write null values, empty collections, or empty arrays to disk.
     * If true, null/empty values will be persisted; otherwise they will be skipped.
     *
     * @return true if null and empty values should be saved
     */
    boolean saveNull() default false;

    /**
     * If the field value equals this value, it will not be written to disk.
     * Supports primitive types, strings, and enums.
     * The value should be provided as a string representation.
     *
     * @return the default value to compare against
     */
    String defaultValue() default "";

    /**
     * Specifies a getter method name that returns a default value to compare against.
     * If the field value equals the value returned by this getter, it will not be written to disk.
     * This must be a non-static method accessible from this class.
     *
     * @return the name of the getter method that provides the default value
     */
    String defaultValueGetter() default "";
}
