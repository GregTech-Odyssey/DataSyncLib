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
 * <h3>Load listener:</h3>
 * <p>{@link #listener()} references a method with signature {@code (T) -> void} that is
 * invoked after the field has been restored from disk. Use it to rebuild state that is
 * derived from the persisted value (caches, lookup tables, dependent fields). It is only
 * called when the field was actually present in the saved data — see {@link #listener()}.</p>
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

    /**
     * Name of a method to invoke <strong>after</strong> this field has been restored from disk.
     *
     * <p>Method signature: {@code (T) -> void}, where {@code T} is the field's declared type.
     * The method must be non-static and is resolved against the field's declaring class
     * (falling back to inherited public methods) using the <em>exact</em> declared parameter
     * type — for an {@code int} field the parameter must be {@code int}, not {@code Integer}:
     * </p>
     *
     * <pre>{@code
     * @SaveToDisk(listener = "onEnergyLoaded")
     * private int energy;
     *
     * private void onEnergyLoaded(int energy) {
     *     // rebuild state derived from the restored value
     * }
     * }</pre>
     *
     * <h3>When it runs:</h3>
     * <ul>
     *   <li>Only when the field's key is present in the loaded {@code field_save} payload,
     *       i.e. the field was written on the previous save; a field skipped by
     *       {@link #condition()}, matched by {@link #defaultValue()}, or absent from older
     *       data does not fire it.</li>
     *   <li>After the decoded value has been assigned to the field (for containers the
     *       contents have already been read in place).</li>
     *   <li>Not on the network sync path ({@code readFromBuffer}), and not for the chunk-load
     *       sync carried by {@code field_sync} — those only trigger
     *       {@code @SyncToClient/@SyncToServer(listener = ...)} and
     *       {@code notifyUpdate}/{@code scheduleUpdate}.</li>
     * </ul>
     *
     * <p>For container-type fields the listener receives the container instance itself, or
     * {@code null} when the stored entry is a null marker (e.g. a {@code createInstance}
     * field saved as null); the listener is never expected to construct the value.</p>
     *
     * @return the name of the load-listener method, or an empty string for none
     * @see #condition()
     */
    String listener() default "";
}
