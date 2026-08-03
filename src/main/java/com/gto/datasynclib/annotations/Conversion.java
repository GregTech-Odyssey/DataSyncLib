package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies a type conversion function to a managed field, allowing the field to be stored
 * as one type while exposing a different type to the DataSyncLib sync and persistence system.
 *
 * <h3>Motivation</h3>
 * <p>Sometimes a field's declared type is not the type you want to synchronize or persist.
 * For example, a {@code CompoundTag} field might be easier to work with as a
 * {@code Map<String, Tag>} for serialization purposes. Rather than maintaining a separate
 * converted field, {@code @Conversion} lets you declare a static {@link java.util.function.Function}
 * field that performs the conversion transparently.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>The annotation's {@link #getFunction()} points to a <strong>static</strong>
 *       {@code Function<A, B>} field declared in the same class (or a parent class)</li>
 *   <li>The function's <strong>input type</strong> ({@code A}) must match the field's
 *       declared type</li>
 *   <li>The function's <strong>return type</strong> ({@code B}) becomes the type that
 *       DataSyncLib manages — it is this type for which codecs, strategies, and factories
 *       are resolved</li>
 *   <li>Optionally, {@link #setFunction()} provides an inverse {@code Function<B, A>}
 *       for converting back when writing values to the field</li>
 * </ol>
 *
 * <h3>Constraints</h3>
 * <ul>
 *   <li>The function field must be {@code static} (the annotation processor calls
 *       {@code field.get(null)} to resolve it)</li>
 *   <li>The function must be declared in the same class as the annotated field
 *       (or a superclass reachable via {@link Class#getDeclaredField})</li>
 *   <li>{@code setFunction} is <strong>optional</strong> — it can be omitted for
 *       {@code final} fields or access-mode fields (those with {@link Access @Access})
 *       because the write path is not used for those modes</li>
 *   <li>If {@code setFunction} is specified but the field is not found, it is silently
 *       ignored (no error is thrown)</li>
 * </ul>
 *
 * <h3>Usage example</h3>
 * <pre>{@code
 * // Convert between CompoundTag (storage) and Map<String, Tag> (sync)
 * private static final Function<CompoundTag, Map<String, Tag>> COMPOUND_TAG_MAP_FUNCTION =
 *     t -> t.tags;
 *
 * @SyncToClient
 * @SaveToDisk
 * @Conversion(getFunction = "COMPOUND_TAG_MAP_FUNCTION")
 * private final CompoundTag tagData = new CompoundTag();
 * // The sync/persistence system now treats this field as Map<String, Tag>
 * }</pre>
 *
 * <h3>With setFunction (for mutable fields)</h3>
 * <pre>{@code
 * private static final Function<MyStorage, MyView> TO_VIEW = MyStorage::toView;
 * private static final Function<MyView, MyStorage> FROM_VIEW = MyView::toStorage;
 *
 * @SyncToClient
 * @Conversion(getFunction = "TO_VIEW", setFunction = "FROM_VIEW")
 * private MyStorage data = new MyStorage();
 * }</pre>
 *
 * @see Access
 * @see com.gto.datasynclib.DataFieldDefinition#conversionGet
 * @see com.gto.datasynclib.DataFieldDefinition#conversionSet
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Conversion {

    /**
     * The name of a <strong>static</strong> {@code Function<A, B>} field declared in
     * the same class (or a parent class).
     *
     * <p>The function converts from the field's declared type ({@code A}) to the
     * type that should be managed by the sync/persistence system ({@code B}).
     * The generic type parameters of this function determine the conversion types —
     * parameter 0 ({@code A}) must match the field type, and parameter 1 ({@code B})
     * becomes the managed type for codec/strategy/factory resolution.</p>
     *
     * @return the static {@code Function} field name (required, must not be empty)
     */
    String getFunction();

    /**
     * The name of a <strong>static</strong> {@code Function<B, A>} field declared in
     * the same class (or a parent class).
     *
     * <p>Provides the inverse conversion: from the managed type ({@code B}) back to
     * the field's declared type ({@code A}). This is called during
     * {@link com.gto.datasynclib.DataFieldDefinition#set DataFieldDefinition.set()}
     * to convert the value before writing it to the actual field.</p>
     *
     * <p>Leave empty (the default) when:
     * <ul>
     *   <li>The field is {@code final} — the setter is never called</li>
     *   <li>The field uses access-mode (annotated with {@link Access @Access}) —
     *       the access layer works with the container in-place</li>
     * </ul>
     *
     * @return the static {@code Function} field name, or {@code ""} if no reverse
     * conversion is needed
     */
    String setFunction() default "";
}
