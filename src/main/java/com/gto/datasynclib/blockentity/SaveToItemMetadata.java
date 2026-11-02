package com.gto.datasynclib.blockentity;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.FieldDefinitionStorage;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;

/**
 * The parsed attributes of one {@link SaveToItem @SaveToItem} field, built once while the class is
 * scanned and stored as {@link FieldDefinitionStorage.AnnotatedField#metadata()}.
 *
 * <p>This is the library's own example of the user-defined metadata an extension annotation can
 * attach: {@link #create} is the {@link FieldDefinitionStorage.AnnotationMetadataFactory} the
 * annotation is registered with, so the item path never re-parses the annotation — it reads the
 * parsed flag from here.</p>
 *
 * <p>A skip condition is not part of this metadata: the item payload is written through
 * {@link com.gto.datasynclib.FieldDataManager#writeFieldsToValue}, so the field's
 * {@link com.gto.datasynclib.annotations.SaveToDisk#skipWhen()} predicate, default value and
 * {@code saveEmpty} rule apply to it as they do to a save file — see {@link SaveToItem}.</p>
 *
 * @see SaveToItem
 * @see FieldDefinitionStorage#registerAnnotation(Class, FieldDefinitionStorage.AnnotationMetadataFactory)
 */
public final class SaveToItemMetadata {

    /**
     * The shared empty result of {@link #cloneFields(Class)}.
     */
    private static final DataFieldDefinition<?>[] NO_FIELDS = new DataFieldDefinition[0];

    /**
     * The clone-writable {@code @SaveToItem} fields of a class, minus the ones whose metadata says
     * {@code saveOnClone = false}.
     *
     * <p>The flag is fixed per field once the class is scanned, so the filtered array is built once
     * per class and the item write never splits the fields again; a class whose fields are all
     * clone-writable gets the storage's own array back, with no copy.</p>
     */
    private static final ClassValue<DataFieldDefinition<?>[]> CLONE_FIELDS = new ClassValue<>() {
        @Override
        protected DataFieldDefinition<?>[] computeValue(Class<?> holderClass) {
            var all = FieldDefinitionStorage.get(holderClass).getAnnotatedFieldDefinitions(SaveToItem.class);
            var annotated = FieldDefinitionStorage.get(holderClass).getAnnotatedFields(SaveToItem.class);
            if (annotated.length == 0) return NO_FIELDS;
            var cloneable = new ArrayList<DataFieldDefinition<?>>(annotated.length);
            for (var field : annotated) {
                if (field.metadata() instanceof SaveToItemMetadata metadata && !metadata.saveOnClone()) continue;
                cloneable.add(field.definition());
            }
            return cloneable.size() == all.length ? all : cloneable.toArray(new DataFieldDefinition[0]);
        }
    };

    /**
     * @param holderClass the block entity class
     * @return its {@code @SaveToItem} fields that are also written into a creative pick-block clone
     */
    static DataFieldDefinition<?>[] cloneFields(Class<?> holderClass) {
        return CLONE_FIELDS.get(holderClass);
    }

    private final boolean saveOnClone;

    private SaveToItemMetadata(boolean saveOnClone) {
        this.saveOnClone = saveOnClone;
    }

    /**
     * The metadata factory of {@link SaveToItem}, given to
     * {@link FieldDefinitionStorage#registerAnnotation(Class, FieldDefinitionStorage.AnnotationMetadataFactory)}.
     *
     * <p>Only parses the annotation here: the flags are plain booleans, so nothing has to be resolved
     * against the field's declaring class.</p>
     *
     * @param lookup     a private-access lookup for the declaring class
     * @param definition the definition built for the annotated field
     * @param type       the field's managed type
     * @param annotation the annotation instance found on the field
     * @return the metadata for that field
     */
    public static SaveToItemMetadata create(MethodHandles.Lookup lookup, DataFieldDefinition<?> definition,
                                            Class<?> type, SaveToItem annotation) {
        return new SaveToItemMetadata(annotation.saveOnClone());
    }

    /**
     * @return {@link SaveToItem#saveOnClone()}: whether the field is also written into a creative
     * pick-block clone
     */
    public boolean saveOnClone() {
        return saveOnClone;
    }
}
