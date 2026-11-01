package com.gto.datasynclib.blockentity;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.FieldDefinitionStorage;
import com.gto.datasynclib.util.ReflectUtil;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * The parsed attributes of one {@link SaveToItem @SaveToItem} field, built once while the class is
 * scanned and stored as {@link FieldDefinitionStorage.AnnotatedField#metadata()}.
 *
 * <p>This is the library's own example of the user-defined metadata an extension annotation can
 * attach: {@link #create} is the {@link FieldDefinitionStorage.AnnotationMetadataFactory} the
 * annotation is registered with, so the item path never re-parses the annotation — it reads the
 * pre-resolved skip predicate and boolean from here.</p>
 *
 * @see SaveToItem
 * @see FieldDefinitionStorage#registerAnnotation(Class, FieldDefinitionStorage.AnnotationMetadataFactory)
 */
public final class SaveToItemMetadata {

    private final boolean saveOnClone;
    @Nullable
    private final MethodHandle skipWhen;

    private SaveToItemMetadata(boolean saveOnClone, @Nullable MethodHandle skipWhen) {
        this.saveOnClone = saveOnClone;
        this.skipWhen = skipWhen;
    }

    /**
     * The metadata factory of {@link SaveToItem}, given to
     * {@link FieldDefinitionStorage#registerAnnotation(Class, FieldDefinitionStorage.AnnotationMetadataFactory)}.
     *
     * <p>Resolves {@link SaveToItem#skipWhen()} to a handle the same way the other annotations
     * resolve their predicates: the method is looked up on the field's declaring class with exactly
     * one parameter of the field's managed type (the {@code toManaged} type for a
     * {@code @Conversion} field), and adapted so reference parameters are widened to {@code Object}
     * while primitive ones stay exact.</p>
     *
     * @param lookup     a private-access lookup for the declaring class
     * @param definition the definition built for the annotated field
     * @param type       the field's managed type
     * @param annotation the annotation instance found on the field
     * @return the metadata for that field
     */
    public static SaveToItemMetadata create(MethodHandles.Lookup lookup, DataFieldDefinition<?> definition,
                                            Class<?> type, SaveToItem annotation) {
        MethodHandle skipWhen = null;
        if (!annotation.skipWhen().isEmpty()) {
            var field = definition.field;
            try {
                var method = ReflectUtil.getAccessibleMethod(field.getDeclaringClass(), annotation.skipWhen(), type);
                method.setAccessible(true);
                skipWhen = ReflectUtil.createAdaptedMethodHandle(lookup, method);
            } catch (RuntimeException e) {
                // the factory runs during the class's one-time scan, so a typo in the attribute
                // would otherwise surface as a bare NoSuchMethodException
                throw new RuntimeException("@SaveToItem(skipWhen = \"" + annotation.skipWhen()
                        + "\") needs a method " + annotation.skipWhen() + "(" + type.getName() + ") on "
                        + field.getDeclaringClass().getName() + ", field "
                        + ReflectUtil.getFieldDetailedName(field), e);
            }
        }
        return new SaveToItemMetadata(annotation.saveOnClone(), skipWhen);
    }

    /**
     * @return {@link SaveToItem#saveOnClone()}: whether the field is also written into a creative
     * pick-block clone
     */
    public boolean saveOnClone() {
        return saveOnClone;
    }

    /**
     * Evaluates {@link SaveToItem#skipWhen()} for one write.
     *
     * <p>{@link MethodHandle#invoke} is used rather than {@code invokeExact} because the item path
     * reads a heterogeneous field set: it performs the boxing/unboxing the handle's type needs.</p>
     *
     * @param source the object that owns the field
     * @param value  the field's current value, after {@code @Conversion}
     * @return {@code true} if the field must not be written into the item
     */
    public boolean skip(Object source, Object value) {
        var skipWhen = this.skipWhen;
        if (skipWhen == null) return false;
        try {
            return (boolean) skipWhen.invoke(source, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
