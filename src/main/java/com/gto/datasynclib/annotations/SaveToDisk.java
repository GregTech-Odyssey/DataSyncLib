package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for automatic persistence to disk (NBT compound data).
 *
 * <p>Annotated fields are automatically written to NBT on chunk save
 * (via {@code BlockEntity.saveAdditional()}) and read back on chunk load
 * (via {@code BlockEntity.load()}). The field value is serialized using the
 * registered {@link com.gto.datasynclib.DataSyncCodec} for the field's type,
 * or via a custom codec specified with {@link Codec @Codec}.</p>
 *
 * <h3>Default value optimization:</h3>
 * <p>If {@link #defaultValue()} or {@link #defaultValueGetter()} is specified,
 * the field value is compared against the default. When they match, the field
 * is <em>not</em> written to disk, saving space. This is useful for fields
 * whose initial/default state is common (e.g., 0, empty, false).</p>
 *
 * <h3>Save condition:</h3>
 * <p>{@link #condition()} references a method with signature {@code (T) -> boolean}.
 * When the method returns {@code true}, the field is <em>skipped</em> entirely.</p>
 *
 * @see SyncToClient
 * @see SyncToServer
 * @see Codec
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
