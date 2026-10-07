package com.gto.datasynclib.remote;

import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.annotations.AdditionalHolder;
import com.gto.datasynclib.util.ReflectUtil;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.experimental.UtilityClass;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Comparator;

/**
 * The holder fields of a class, numbered the way a call addresses them, and the walk that follows a
 * call's field-index path to its target.
 *
 * <p>A nested holder writes the index of the field that holds it — the {@code @AdditionalHolder} (or
 * plain {@link IFieldDataHolder}-typed) field it sits in — and, for deeper nesting, one index per level
 * up to the root. The receiving side starts at the object the packet addressed and reads that many
 * fields: index {@code 0} of the block entity's table is its first holder field, and so on. An empty
 * path means the addressed object itself.</p>
 *
 * <h3>Why the numbering is stable</h3>
 * <p>An index is only useful if both sides number the same fields the same way, so the table is built
 * deterministically: superclass fields first, then each class's own fields <strong>sorted by field
 * name</strong> ({@link Class#getDeclaredFields()} order is unspecified), and a field that cannot be
 * unreflected is skipped on both sides identically. Reflection happens once per class
 * ({@link ClassValue}), never per call.</p>
 *
 * @see RemoteInvoker#write(RemoteMethod, int[], Object...)
 */
@UtilityClass
public class RemoteRouting {

    private final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /**
     * The path of the addressed object itself — shared, because it is empty and never mutated.
     */
    public final int[] EMPTY_PATH = new int[0];

    /**
     * How many holder levels a call may descend — the bound the send side can produce too, so a
     * malformed depth neither allocates arbitrarily nor walks forever.
     */
    public final int MAX_DEPTH = 8;

    /**
     * The holder fields of one class in wire order: the accessor at index {@code n} is what an index
     * {@code n} in a call path refers to, and {@code names} is the same order for diagnostics.
     */
    private record Fields(VarHandle[] accessors, String[] names) {
    }

    private final ClassValue<Fields> HOLDER_FIELDS = new ClassValue<>() {
        @Override
        protected Fields computeValue(Class<?> type) {
            // Superclass first, so the root's own fields lead the numbering.
            var hierarchy = new ObjectArrayList<Class<?>>(4);
            for (var current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                hierarchy.add(current);
            }
            var accessors = new ObjectArrayList<VarHandle>(4);
            var names = new ObjectArrayList<String>(4);
            for (int level = hierarchy.size() - 1; level >= 0; level--) {
                var clazz = hierarchy.get(level);
                var fields = new ObjectArrayList<Field>(2);
                for (var field : clazz.getDeclaredFields()) {
                    // static and synthetic fields are never routable targets: a static field is not
                    // instance state and a synthetic one is the compiler's back-reference of an inner
                    // class ({@code this$0}). A {@code transient} holder-typed field is skipped as well,
                    // which is the optional way to keep an owner link
                    // (IFieldDataHolder#getParentHolder()) out of the numbering — write it or not, both
                    // sides number the same class the same way, so a holder works either way.
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())
                            || field.isSynthetic()) continue;
                    if (field.getAnnotation(AdditionalHolder.class) == null
                            && !IFieldDataHolder.class.isAssignableFrom(field.getType())) continue;
                    fields.add(field);
                }
                if (fields.isEmpty()) continue;
                // Sorted on purpose: the declared order is not specified by the JVM, and the sender and
                // the receiver must agree on which field an index names.
                fields.sort(FIELD_NAME_ORDER);
                MethodHandles.Lookup lookup;
                try {
                    lookup = MethodHandles.privateLookupIn(clazz, LOOKUP);
                } catch (IllegalAccessException e) {
                    continue;
                }
                for (int i = 0; i < fields.size(); i++) {
                    var field = fields.get(i);
                    try {
                        accessors.add(ReflectUtil.createVarHandle(lookup, field));
                        names.add(field.getName());
                    } catch (Throwable ignored) {
                        // A field that cannot be unreflected is simply not addressable — and it is
                        // skipped identically on both sides, so the numbering still agrees.
                    }
                }
            }
            return new Fields(accessors.toArray(new VarHandle[0]), names.toArray(new String[0]));
        }
    };

    /**
     * Field-name order, reused by every class's build so the numbering does not depend on the
     * unspecified order {@link Class#getDeclaredFields()} returns.
     */
    private final Comparator<Field> FIELD_NAME_ORDER = Comparator.comparing(Field::getName);

    /**
     * The holder fields of a class, indexed the way a call path refers to them.
     */
    public VarHandle[] holderFields(Class<?> type) {
        return HOLDER_FIELDS.get(type).accessors();
    }

    /**
     * The field names of a class in wire order — index {@code n} names the field a path index
     * {@code n} reads. Used for diagnostics, so an error can name the field it could not resolve.
     */
    public String[] holderFieldNames(Class<?> type) {
        return HOLDER_FIELDS.get(type).names();
    }

    /**
     * The wire index of the holder field of {@code owner} that holds {@code holder} — found by identity,
     * because a holder knows its owner but not which of the owner's fields it was assigned to.
     *
     * <p>Walks the owner's holder fields in wire order and compares the values, so it is a handful of
     * field reads; the caller ({@link com.gto.datasynclib.FieldDataManager#remotePath()}) does it once
     * per holder and caches the result.</p>
     *
     * @param type   the owner's class (the table to read)
     * @param owner  the owning holder instance
     * @param holder the nested holder to locate
     * @return the field index, or {@code -1} when no holder field of {@code owner} is {@code holder}
     */
    public int indexOfHolder(Class<?> type, Object owner, Object holder) {
        var accessors = HOLDER_FIELDS.get(type).accessors();
        for (int index = 0; index < accessors.length; index++) {
            try {
                if (accessors[index].get(owner) == holder) return index;
            } catch (Throwable ignored) {
                // An unreadable field is simply not the one that holds it.
            }
        }
        return -1;
    }
}
