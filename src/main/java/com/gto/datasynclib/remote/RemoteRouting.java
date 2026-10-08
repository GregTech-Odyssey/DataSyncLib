package com.gto.datasynclib.remote;

import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.annotations.AdditionalHolder;
import com.gto.datasynclib.util.ReflectUtil;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Comparator;

/**
 * The holder tree of a class, flattened into the fixed order a remote call addresses it by.
 *
 * <p>A call carries one number: the position of its target in the sender's holder tree. Both sides get
 * that number from the same place — the class-level table built here — so the sender does not have to
 * know where it sits in the tree and the receiver does not have to walk one: index {@code 0} is the
 * object the packet addressed (the root), {@code 1} is the first holder field of it, {@code 2} the next
 * one to be found depth-first, and so on. The tree is fixed by the classes, so the concatenation is
 * fixed too, and resolving an index is just a few {@link VarHandle} reads along one cached path.</p>
 *
 * <h3>Why the order is stable</h3>
 * <p>An index is only useful if both sides count the same nodes in the same order, so the table is built
 * deterministically: superclass fields first, then each class's own fields <strong>sorted by field
 * name</strong> ({@link Class#getDeclaredFields()} order is unspecified), depth-first so a nested tree
 * reads as a flat sequence. Static, synthetic and {@code transient} fields are never nodes, and a field
 * that cannot be unreflected is skipped identically on both sides. Reflection happens once per class
 * ({@link ClassValue}), never per call.</p>
 *
 * <p>A path whose intermediate field is {@code null} on a given instance simply resolves to nothing: the
 * node is absent there, and the index it owns is never produced (send) or reported as an error (receive).</p>
 *
 * @see RemoteInvoker#write(RemoteMethod, int, Object...)
 */
@UtilityClass
public class RemoteRouting {

    private final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /**
     * How deep the tree may go — the bound the table build walks to, so a self-referencing holder graph
     * cannot make it loop.
     */
    public final int MAX_DEPTH = 8;

    /**
     * The holder fields of one class in wire order: the accessor at index {@code n} is what an index
     * {@code n} in a call path refers to, and {@code names} is the same order for diagnostics.
     */
    private record Fields(VarHandle[] accessors, String[] names) {
    }

    /**
     * The flattened tree of one class: one accessor path per addressable node, in wire order, plus the
     * name of each node. Index {@code 0} of the wire order is the root itself and has no path.
     */
    private record Tree(VarHandle[][] paths, String[] names) {
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
                    // which is the optional way to keep a field out of the numbering — write it or not,
                    // both sides build the same table, so a holder works either way.
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

    private final ClassValue<Tree> TREES = new ClassValue<>() {
        @Override
        protected Tree computeValue(Class<?> type) {
            var paths = new ObjectArrayList<VarHandle[]>(8);
            var names = new ObjectArrayList<String>(8);
            collect(type, new ObjectArrayList<VarHandle>(4), new ObjectArrayList<String>(4),
                    new ObjectArrayList<Class<?>>(4), paths, names);
            return new Tree(paths.toArray(new VarHandle[0][]), names.toArray(new String[0]));
        }
    };

    /**
     * Depth-first walk of the class tree: each holder field contributes its own node, then the nodes of
     * its subtree, in wire order — the order a flat index refers to.
     *
     * <p>Node names are qualified by their path ({@code "b.inner"}), so two siblings of the same class
     * are still told apart in a diagnostic — the index already tells them apart on the wire, and the
     * method table they share is per class, exactly as it should be.</p>
     *
     * @param path     the accessors from the root to {@code type}
     * @param namePath the field names along {@code path}, for the qualified node names
     * @param onPath   the classes on the current path, so a self-referencing type contributes its first
     *                 level and then stops
     */
    private void collect(Class<?> type, ObjectArrayList<VarHandle> path, ObjectArrayList<String> namePath,
                         ObjectArrayList<Class<?>> onPath,
                         ObjectArrayList<VarHandle[]> paths, ObjectArrayList<String> names) {
        if (type == null || type == Object.class || path.size() > MAX_DEPTH) return;
        for (var clazz : onPath) {
            if (clazz == type) return;
        }
        onPath.add(type);
        var fields = HOLDER_FIELDS.get(type);
        for (int i = 0; i < fields.accessors().length; i++) {
            var accessor = fields.accessors()[i];
            path.add(accessor);
            namePath.add(fields.names()[i]);
            paths.add(path.toArray(new VarHandle[0]));
            names.add(String.join(".", namePath));
            var fieldType = accessor.varType();
            if (IFieldDataHolder.class.isAssignableFrom(fieldType) || fieldType.getAnnotation(AdditionalHolder.class) != null
                    || fieldType.getDeclaredFields().length > 0) {
                collect(fieldType, path, namePath, onPath, paths, names);
            }
            path.remove(path.size() - 1);
            namePath.remove(namePath.size() - 1);
        }
        onPath.remove(onPath.size() - 1);
    }

    /**
     * Field-name order, reused by every class's build so the numbering does not depend on the
     * unspecified order {@link Class#getDeclaredFields()} returns.
     */
    private final Comparator<Field> FIELD_NAME_ORDER = Comparator.comparing(Field::getName);

    /**
     * The holder fields of a class, in wire order.
     */
    public VarHandle[] holderFields(Class<?> type) {
        return HOLDER_FIELDS.get(type).accessors();
    }

    /**
     * The field names of a class in wire order — index {@code n} names the field an index {@code n}
     * reads. Used for diagnostics.
     */
    public String[] holderFieldNames(Class<?> type) {
        return HOLDER_FIELDS.get(type).names();
    }

    /**
     * The flattened node names of a class's holder tree, in wire order: index {@code 0} is the root
     * itself ({@code "self"}), then one name per addressable node. For diagnostics and tests, and the
     * readable form of what a call index means.
     */
    public String[] holderNodeNames(Class<?> type) {
        var names = TREES.get(type).names();
        var all = new String[names.length + 1];
        all[0] = "self";
        System.arraycopy(names, 0, all, 1, names.length);
        return all;
    }

    /**
     * How many nodes a class's holder tree has, the root included — the size of the index space a call
     * addresses.
     */
    public int holderNodeCount(Class<?> type) {
        return TREES.get(type).paths().length + 1;
    }

    /**
     * The wire index of {@code target} inside {@code root}'s holder tree — the number a call is addressed
     * with.
     *
     * <p>Index {@code 0} is the root itself; every other index is a node of the flattened tree, compared
     * by identity against the object the cached path actually reaches. Cost is a few {@link VarHandle}
     * reads per candidate, on a table that was built once per class.</p>
     *
     * @param root   the object a packet will address (a block entity, an entity, or any holder)
     * @param target the holder the call is for; {@code root} itself is allowed
     * @return the index, or {@code -1} when {@code target} is not part of {@code root}'s tree
     */
    public int indexOf(Object root, Object target) {
        if (root == target) return 0;
        var paths = TREES.get(root.getClass()).paths();
        for (int index = 0; index < paths.length; index++) {
            if (resolve(root, paths[index]) == target) return index + 1;
        }
        return -1;
    }

    /**
     * The object a call index refers to on this instance — the inverse of
     * {@link #indexOf(Object, Object)}.
     *
     * @param root  the object the packet addressed
     * @param index the wire index, {@code 0} for the root itself
     * @return the node, or {@code null} when the index is out of range or the node is absent on this
     * instance (an intermediate holder field is {@code null})
     */
    @Nullable
    public Object nodeAt(Object root, int index) {
        if (index == 0) return root;
        var paths = TREES.get(root.getClass()).paths();
        if (index < 0 || index > paths.length) return null;
        return resolve(root, paths[index - 1]);
    }

    /**
     * Follows one cached accessor path from the root; {@code null} as soon as a field on the way is
     * absent (or unreadable).
     */
    @Nullable
    private Object resolve(Object root, VarHandle[] path) {
        Object node = root;
        for (var accessor : path) {
            if (node == null) return null;
            try {
                node = accessor.get(node);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return node;
    }
}
