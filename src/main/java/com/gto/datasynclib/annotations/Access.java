package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Forces a field to use <strong>access-mode</strong> synchronization instead of value-mode.
 *
 * <p>By default, non-final fields use value-mode (the entire value is read, compared,
 * and written as a unit). Final fields and fields annotated with {@code @Access} use
 * access-mode, which works with the mutable container itself (tracking instance identity
 * changes and delegating to the container's own change detection).</p>
 *
 * <p>This is useful for mutable containers (collections, maps, arrays) where the field
 * reference never changes but the <em>contents</em> do. Without this annotation, the
 * system would compare by reference identity alone and miss internal mutations.</p>
 *
 * <h3>When to use:</h3>
 * <ul>
 *   <li>Non-final {@code Collection}, {@code Map}, or array fields whose contents mutate</li>
 *   <li>Non-final fields whose type implements {@link com.gto.datasynclib.IFieldDataHolder}
 *       or {@link com.gto.datasynclib.IDataSerializable}</li>
 *   <li>Any non-final field where you want access-mode semantics (mutable container tracking)</li>
 * </ul>
 *
 * @see com.gto.datasynclib.field.access.AbstractFieldAccess
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Access {

    /**
     * When {@code true}, the access layer will encode/decode the container instance itself
     * (handling null-vs-value and instance creation) rather than assuming the instance
     * always exists. Required when the field may be {@code null} at any point and needs
     * to be serialized/deserialized as a whole.
     *
     * <p>This also enables a legacy data version ({@code dataVersion == -1}) migration path
     * in {@link com.gto.datasynclib.field.access.AbstractFieldAccess#readFromData}.</p>
     *
     * @return {@code true} if the instance itself should be persisted and restored,
     * {@code false} (default) if the instance always exists and only its
     * contents need serialization
     */
    boolean createInstance() default false;
}
