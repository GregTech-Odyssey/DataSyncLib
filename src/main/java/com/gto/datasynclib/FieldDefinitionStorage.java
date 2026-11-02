package com.gto.datasynclib;

import com.gto.datasynclib.annotations.*;
import com.gto.datasynclib.blockentity.SaveToItem;
import com.gto.datasynclib.blockentity.SaveToItemMetadata;
import com.gto.datasynclib.field.access.ChildManagerAccess;
import com.gto.datasynclib.field.object.ObjCodecField;
import com.gto.datasynclib.util.HashUtil;
import com.gto.datasynclib.util.ReflectUtil;
import com.gto.datasynclib.util.cache.HashMapCache;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.*;

/**
 * Central registry and cache for {@link DataFieldDefinition} instances discovered from annotated classes.
 *
 * <p>Scans class fields for {@code @SaveToDisk}, {@code @SyncToClient}, {@code @SyncToServer} and
 * {@code @AddToManager}, plus every extension annotation registered through
 * {@link #registerAnnotation(Class, AnnotationMetadataFactory)}, constructing
 * {@link DataFieldDefinition} entries that drive the field synchronization and persistence
 * lifecycle managed by {@link FieldDataManager}.</p>
 *
 * <p>Supports a pluggable factory system for field types ({@link #registerFactory}),
 * access types ({@link #registerAccessFactory}), and generic types ({@link #registerGenericFactory}),
 * allowing custom {@link DataField} implementations for different field categories.</p>
 *
 * <p>Strategy registration ({@link #registerStrategy}) allows custom hash/equality functions
 * for change detection on complex types.</p>
 *
 * <h3>How a field type is resolved</h3>
 * <p>Every managed field is resolved once, in this order, and the result is cached per type:</p>
 * <ol>
 *   <li>{@code @Access} or a {@code final} field → the <strong>access</strong> chain
 *       ({@link #registerAccessFactory}, {@link #registerAccessInterfaceFactory},
 *       {@link #registerAccessCustomFactory})</li>
 *   <li>{@code @Codec} → codec-backed object field, no factory lookup</li>
 *   <li>{@code @Generic} → the <strong>generic</strong> chain
 *       ({@link #registerGenericFactory}, {@link #registerCustomGenericFactory})</li>
 *   <li>otherwise the <strong>value</strong> chain ({@link #registerFactory},
 *       {@link #registerInterfaceFactory}, {@link #registerCustomFactory}) is tried first,
 *       then generic, then access</li>
 * </ol>
 *
 * <p>Because the outcome is cached, register custom types <em>before</em> the first holder of
 * that type is scanned (i.e. during mod construction, like the {@code DataSyncLib} constructor
 * does for the built-in types). Registration methods synchronize on their backing table, but
 * lookups on the hot path do not, so concurrent registration is not supported.</p>
 *
 * <h3>Extension annotations</h3>
 * <p>A downstream mod can bring its own field annotations: {@link #registerAnnotation(Class)}
 * makes a scan record every field carrying that annotation, so the annotation becomes a query
 * key for the class's storage. Each record pairs the annotation instance with the
 * {@link DataFieldDefinition} built for the field ({@link AnnotatedField}) and is read back
 * with {@link #getAnnotatedFields(Class)}.</p>
 *
 * <p>A registered annotation also <em>manages</em> its field on its own, exactly like
 * {@code @AddToManager}: the field needs no {@code @SaveToDisk}/{@code @SyncTo*} of its own,
 * but is resolved through the normal factory chain and takes part in the manager's bulk
 * {@code writeAllToValue}/{@code readAllFromValue} paths. This is what makes the stored
 * definition usable — an annotation consumer can read and write the field through it.</p>
 *
 * <p><strong>User metadata:</strong>
 * {@link #registerAnnotation(Class, AnnotationMetadataFactory)} additionally takes a factory that
 * runs once per annotated field during the scan and turns the annotation's attributes into an
 * arbitrary object, stored as {@link AnnotatedField#metadata()}. That keeps the parsing (and the
 * method handles it usually needs) out of the annotation itself and out of the field definition:
 * the consumer just reads the parsed form. The library uses it for its own
 * {@link SaveToItem @SaveToItem}, whose attributes become a {@link SaveToItemMetadata}.</p>
 *
 * <p>The registration belongs in mod construction next to the factory registrations: the
 * annotations present on a class are collected during its one scan, and a class's storage is
 * cached forever, so an annotation registered later is absent from already-scanned classes.</p>
 */
public final class FieldDefinitionStorage {

    // ==================================================================================
    // State
    // ==================================================================================

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    // ---- value-mode factories ----

    /**
     * Exact-type value-mode registrations; consulted before the predicate chain.
     */
    private static final Reference2ReferenceOpenHashMap<Class<?>, DataField.Factory<?>> EXACT_FIELDS = new Reference2ReferenceOpenHashMap<>();

    private static final ArrayList<DataField.CustomFactory<?>> FIELDS = new ArrayList<>();

    private static final ClassValue<DataField.Factory<?>> FIELDS_CACHE = new ClassValue<>() {
        @Override
        protected DataField.Factory<?> computeValue(Class<?> type) {
            var exact = EXACT_FIELDS.get(type);
            if (exact != null) return exact;
            for (var f : FIELDS) {
                if (f.predicate().test(type)) {
                    return f.factory().apply(type);
                }
            }
            throw new IllegalStateException("No factory for " + type);
        }
    };

    // ---- access-mode factories ----

    /**
     * Exact-type access-mode registrations; consulted before the predicate chain.
     */
    private static final Reference2ReferenceOpenHashMap<Class<?>, DataField.Factory<?>> EXACT_ACCESS = new Reference2ReferenceOpenHashMap<>();

    private static final ArrayList<DataField.CustomFactory<?>> ACCESS = new ArrayList<>();

    private static final ClassValue<DataField.Factory<?>> ACCESS_CACHE = new ClassValue<>() {
        @Override
        protected DataField.Factory<?> computeValue(Class<?> type) {
            var exact = EXACT_ACCESS.get(type);
            if (exact != null) return exact;
            for (var f : ACCESS) {
                if (f.predicate().test(type)) {
                    return f.factory().apply(type);
                }
            }
            throw new IllegalStateException("No factory for " + type);
        }
    };

    // ---- generic factories ----

    private static final ArrayList<DataField.CustomGenericFactory<?>> GENERIC_FIELDS = new ArrayList<>();

    private static Function<Supplier<Class<?>[]>, DataField.Factory<?>> genericFunction(Class<?> type) {
        return genericType -> {
            for (var f : GENERIC_FIELDS) {
                if (f.predicate().test(type, genericType.get())) {
                    return f.factory().apply(type, genericType.get());
                }
            }
            throw new IllegalStateException("No factory for " + type);
        };
    }

    private static final ClassValue<HashMapCache<Supplier<Class<?>[]>, DataField.Factory<?>>> GENERIC_FIELDS_CACHE =
            new ClassValue<>() {
                @Override
                protected HashMapCache<Supplier<Class<?>[]>, DataField.Factory<?>> computeValue(Class<?> type) {
                    return new HashMapCache<>(genericFunction(type));
                }
            };

    // ---- strategies ----

    private static final Reference2ReferenceOpenHashMap<Class<?>, Hash.Strategy<?>> STRATEGIES = new Reference2ReferenceOpenHashMap<>();

    // ---- extension annotations ----

    /**
     * Extension annotations registered by downstream mods, in registration order.
     */
    private static final ReferenceLinkedOpenHashSet<Class<? extends Annotation>> EXTENSION_ANNOTATIONS = new ReferenceLinkedOpenHashSet<>();

    /**
     * The optional metadata factory of each registered annotation, see
     * {@link #registerAnnotation(Class, AnnotationMetadataFactory)}.
     */
    private static final Reference2ReferenceOpenHashMap<Class<? extends Annotation>, AnnotationMetadataFactory<?>> EXTENSION_ANNOTATION_FACTORIES = new Reference2ReferenceOpenHashMap<>();

    /**
     * {@link #EXTENSION_ANNOTATIONS} with their factories as a plain array, replaced on every
     * registration: a scan reads the current snapshot once, so it needs no lock and never sees a
     * half-added entry.
     */
    private static volatile RegisteredAnnotation[] EXTENSION_ANNOTATIONS_SNAPSHOT = new RegisteredAnnotation[0];

    /**
     * The probe result of a field that carries none of the registered annotations.
     */
    private static final MatchedAnnotation[] NO_EXTENSION_ANNOTATIONS = new MatchedAnnotation[0];

    /**
     * The shared empty result of {@link #getAnnotatedFields(Class)}: returned as-is instead of a
     * fresh array, since a class without extension annotations asks for it on every lookup.
     */
    private static final AnnotatedField<?>[] NO_ANNOTATED_FIELDS = new AnnotatedField[0];

    /**
     * The shared empty result of {@link #getAnnotatedFieldDefinitions(Class)}, for the same reason.
     */
    private static final DataFieldDefinition<?>[] NO_ANNOTATED_DEFINITIONS = new DataFieldDefinition[0];

    // ---- per-class storage cache ----

    private static final FieldDefinitionStorage EMPTY = new FieldDefinitionStorage();

    /**
     * Per-class definition cache. A {@link ClassValue} keeps the entry on the class itself instead
     * of in a class-keyed map: no mod class is pinned in memory by the cache, the superclass walk
     * re-enters it naturally, and {@code get} is a direct per-class read rather than a map lookup.
     */
    private static final ClassValue<FieldDefinitionStorage> CACHE = new ClassValue<>() {
        @Override
        protected FieldDefinitionStorage computeValue(Class<?> clazz) {
            Class<?> sc = clazz.getSuperclass();
            if (sc != null && sc != Object.class) {
                var sh = CACHE.get(sc);
                var storage = of(clazz, sh);
                return storage.allDefinitions.length == sh.allDefinitions.length ? sh : storage;
            }
            var storage = of(clazz);
            return storage.allDefinitions.length == 0 ? EMPTY : storage;
        }
    };

    // ==================================================================================
    // Registration API
    // ==================================================================================

    /**
     * Registers a value-mode factory for an exact field type (primitives, codec-backed objects).
     * Takes precedence over the predicate chain and over the generic/access tiers.
     *
     * <p>The registration is consulted by the per-type {@link ClassValue} that memoizes the
     * resolved factory, so it must arrive before the first field of that type is resolved — a
     * later registration cannot replace an already-resolved entry.</p>
     *
     * @param type    the exact field type to handle
     * @param factory creates the {@link DataField} for a field definition
     */
    public static <T> void registerFactory(Class<T> type, DataField.Factory<T> factory) {
        synchronized (EXACT_FIELDS) {
            EXACT_FIELDS.put(type, factory);
        }
    }

    /**
     * Registers a value-mode factory for every type assignable to {@code interfaceType}.
     *
     * @param interfaceType the interface (or superclass) whose implementors are matched
     * @param factory       receives the concrete field type and returns the factory to use
     * @param priority      higher wins
     */
    public static <T> void registerInterfaceFactory(Class<T> interfaceType, Function<Class<?>, DataField.Factory<T>> factory, int priority) {
        registerCustomFactory(interfaceType::isAssignableFrom, factory, priority);
    }

    /**
     * Registers a value-mode factory behind an arbitrary type predicate — the main hook for
     * making custom (non-container) types serializable, e.g. any type with a registered codec.
     *
     * <p>Entries are kept sorted by descending priority and the first matching predicate wins;
     * the default predicate set installs codec-backed objects at priority 100.</p>
     *
     * @param type     predicate deciding whether this factory handles a field type
     * @param factory  receives the concrete field type and returns the factory to use
     * @param priority higher values are evaluated first
     */
    public static <T> void registerCustomFactory(Predicate<Class<?>> type, Function<Class<?>, DataField.Factory<T>> factory, int priority) {
        synchronized (FIELDS) {
            FIELDS.add(new DataField.CustomFactory<>(type, factory, priority));
            FIELDS.sort(DataField.CustomFactory.COMPARATOR);
        }
    }

    /**
     * Registers an access-mode factory for an exact field type, bypassing the predicate chain.
     *
     * <p>Exact-type registrations are stored directly in the type→factory cache, so they win
     * over any {@code registerCustomFactory}/{@code registerInterfaceFactory} predicate. Like
     * every registration here it must happen before the first holder of that type is scanned —
     * the resolved factory is cached per type and a later registration is ignored.</p>
     *
     * @param type    the exact field type to handle
     * @param factory creates the {@link DataField} for a field definition
     */
    public static <T> void registerAccessFactory(Class<T> type, DataField.Factory<T> factory) {
        synchronized (EXACT_ACCESS) {
            EXACT_ACCESS.put(type, factory);
        }
    }

    /**
     * Registers an access-mode factory for every type assignable to {@code interfaceType}.
     *
     * @param interfaceType the interface (or superclass) whose implementors are matched
     * @param factory       receives the concrete field type and returns the factory to use
     * @param priority      higher wins; the access chain is consulted after the value chain
     * @see #registerAccessCustomFactory(Predicate, Function, int)
     */
    public static <T> void registerAccessInterfaceFactory(Class<T> interfaceType, Function<Class<?>, DataField.Factory<T>> factory, int priority) {
        registerAccessCustomFactory(interfaceType::isAssignableFrom, factory, priority);
    }

    /**
     * Registers an access-mode factory behind an arbitrary type predicate.
     *
     * <p>The access chain is the last tier of field resolution (containers, holders, arrays),
     * so these predicates are only reached when no value-mode factory matched. Entries are kept
     * sorted by descending priority and the first match wins.</p>
     *
     * @param type     predicate deciding whether this factory handles a field type
     * @param factory  receives the concrete field type and returns the factory to use
     * @param priority higher values are evaluated first
     */
    public static <T> void registerAccessCustomFactory(Predicate<Class<?>> type, Function<Class<?>, DataField.Factory<T>> factory, int priority) {
        synchronized (ACCESS) {
            ACCESS.add(new DataField.CustomFactory<>(type, factory, priority));
            ACCESS.sort(DataField.CustomFactory.COMPARATOR);
        }
    }

    /**
     * Registers a factory for an exact parameterized type, e.g. {@code MyType.class} with
     * {@code String.class, Integer.class} for {@code MyType<String, Integer>}.
     *
     * <p>The generic arguments are matched by identity against the field's resolved type
     * arguments (see {@link ReflectUtil#getFieldGenericTypeClasses}), so register the same
     * {@link Class} instances that appear in the field declaration.</p>
     *
     * @param type        the raw field type
     * @param factory     creates the {@link DataField} for a field definition
     * @param genericType the resolved generic arguments, in declaration order
     */
    public static <T> void registerGenericFactory(Class<?> type, DataField.Factory<T> factory, Class<?>... genericType) {
        var cache = GENERIC_FIELDS_CACHE.get(type);
        synchronized (cache) {
            cache.put(HashUtil.arrayIdentityWrapper(genericType), factory);
        }
    }

    /**
     * Registers a generic factory behind a predicate over {@code (rawType, genericArguments)}.
     *
     * <p>Used for parameterized types that cannot be matched by an exact class, such as
     * {@code Map<K, V>} or {@code List<T>}; the predicate receives the resolved argument classes.
     * Entries are evaluated in descending priority order and the first match wins.</p>
     *
     * @param type     predicate over the raw type and its resolved generic arguments
     * @param factory  builds the factory for a matching type
     * @param priority higher values are evaluated first
     */
    public static <T> void registerCustomGenericFactory(BiPredicate<Class<?>, Class<?>[]> type, BiFunction<Class<?>, Class<?>[], DataField.Factory<T>> factory, int priority) {
        synchronized (GENERIC_FIELDS) {
            GENERIC_FIELDS.add(new DataField.CustomGenericFactory<>(type, factory, priority));
            GENERIC_FIELDS.sort(DataField.CustomGenericFactory.COMPARATOR);
        }
    }

    /**
     * Registers the hash/equality strategy used for change detection and default-value comparison
     * of an exact field type.
     *
     * <p>Without a registration the framework falls back to
     * {@link DataFieldDefinition#OBJECT_STRATEGY} (ordinary {@code equals}/{@code hashCode}).
     * The library pre-registers strategies for {@link net.minecraft.world.item.ItemStack} and
     * {@link net.minecraftforge.fluids.FluidStack}, and for the array types {@code ItemStack[]} and
     * {@code FluidStack[]} (an array field looks up its own exact type, so it does not inherit the
     * element's strategy). For an array of another element type, derive its strategy from the
     * element's with {@link com.gto.datasynclib.util.HashUtil#arrayStrategy(Hash.Strategy)} instead of
     * writing one by hand. A {@code @Strategy} annotation on a field overrides this map.</p>
     *
     * @param type     the exact field type the strategy applies to
     * @param strategy the strategy to use
     */
    public static <T> void registerStrategy(Class<T> type, Hash.Strategy<T> strategy) {
        synchronized (STRATEGIES) {
            STRATEGIES.put(type, strategy);
        }
    }

    /**
     * Registers a downstream field annotation without metadata, so that scanning a class records
     * the fields that carry it.
     *
     * <p>Shorthand for {@link #registerAnnotation(Class, AnnotationMetadataFactory)} with
     * {@code null}: the recorded {@link AnnotatedField#metadata()} is {@code null}.</p>
     *
     * @param annotationType the annotation to record on scanned fields
     * @throws IllegalArgumentException if {@code annotationType} is not a runtime-retained field
     *                                  annotation
     */
    public static void registerAnnotation(Class<? extends Annotation> annotationType) {
        registerAnnotation(annotationType, null);
    }

    /**
     * Registers a downstream field annotation, so that scanning a class records the fields that
     * carry it, together with an optional piece of metadata built once per field.
     *
     * <p><strong>What is recorded:</strong> for every field annotated with {@code annotationType},
     * the scan stores a triple {@code (definition, annotation instance, metadata)} under the
     * annotation type in the class's storage, read back with
     * {@link #getAnnotatedFields(Class)}. A field may carry several registered annotations (one
     * entry each) and a class may carry one annotation on several fields.</p>
     *
     * <p><strong>The annotation manages its field:</strong> like {@link AddToManager}, carrying a
     * registered annotation is enough to put a field under management — it needs no
     * {@code @SaveToDisk}/{@code @SyncTo*} of its own, is resolved through the usual factory chain
     * (so the field's type must be supported, as for any managed field), and takes part in the
     * manager's bulk {@code writeAllToValue}/{@code readAllFromValue} paths (but not in automatic
     * sync or incremental save, unless it also carries those annotations). That is what makes the
     * stored definition usable: an annotation consumer can read and write the field through it,
     * whatever the annotation means to the mod that declared it.</p>
     *
     * <p><strong>The metadata factory</strong> is the place for the parsed form of an annotation's
     * attributes: it runs once per annotated field during the scan and receives the private-access
     * {@link MethodHandles.Lookup} for the declaring class (so it can build
     * {@link java.lang.invoke.MethodHandle}s with
     * {@link ReflectUtil#createAdaptedMethodHandle}), the {@link DataFieldDefinition} built for the
     * field (its {@link DataFieldDefinition#field} is the reflected field), the field's managed type
     * (the {@code toManaged} type of a {@code @Conversion} field) and the annotation instance. Its
     * result is stored as {@link AnnotatedField#metadata()} and never recomputed — {@code null}
     * means "no metadata". This is how the library itself handles {@link SaveToItem @SaveToItem},
     * whose attributes become a {@link SaveToItemMetadata}.</p>
     *
     * <p><strong>Register before the first scan:</strong> the annotations a class carries are
     * collected during its one scan and the resulting storage is cached for the lifetime of the
     * class, so an annotation registered after a holder class has been scanned does not appear in
     * that class's storage. Register during mod construction, next to the factory registrations.</p>
     *
     * <p>The annotation must be {@code @Retention(RetentionPolicy.RUNTIME)} — anything shorter is
     * invisible to reflection — and {@code @Target(ElementType.FIELD)}; both are validated here.
     * Registering the same annotation again is a no-op, except that a metadata factory arriving with
     * a later registration is attached if the earlier one had none: a shared registration path may
     * add the annotation first and its factory later, and the factory would otherwise be lost.</p>
     *
     * @param annotationType  the annotation to record on scanned fields
     * @param metadataFactory builds the per-field metadata, or {@code null} for none
     * @param <A>             the annotation type
     * @throws IllegalArgumentException if {@code annotationType} is not a runtime-retained field
     *                                  annotation
     */
    public static <A extends Annotation> void registerAnnotation(Class<A> annotationType, @Nullable AnnotationMetadataFactory<A> metadataFactory) {
        Objects.requireNonNull(annotationType, "annotationType");
        if (!annotationType.isAnnotation())
            throw new IllegalArgumentException("Not an annotation type: " + annotationType.getName());
        var retention = annotationType.getAnnotation(Retention.class);
        if (retention == null || retention.value() != RetentionPolicy.RUNTIME)
            throw new IllegalArgumentException("Annotation " + annotationType.getName()
                    + " must be @Retention(RetentionPolicy.RUNTIME) to be registered");
        var target = annotationType.getAnnotation(Target.class);
        if (target != null && Arrays.asList(target.value()).indexOf(ElementType.FIELD) < 0)
            throw new IllegalArgumentException("Annotation " + annotationType.getName()
                    + " must be @Target(ElementType.FIELD) to be registered");
        synchronized (EXTENSION_ANNOTATIONS) {
            var added = EXTENSION_ANNOTATIONS.add(annotationType);
            var factoryAdded = metadataFactory != null
                    && EXTENSION_ANNOTATION_FACTORIES.putIfAbsent(annotationType, metadataFactory) == null;
            // both already known with the same effect: nothing changes, so the snapshot can stay
            if (!added && !factoryAdded) return;
            var snapshot = new RegisteredAnnotation[EXTENSION_ANNOTATIONS.size()];
            var index = 0;
            for (var type : EXTENSION_ANNOTATIONS) {
                snapshot[index++] = new RegisteredAnnotation(type, EXTENSION_ANNOTATION_FACTORIES.get(type));
            }
            EXTENSION_ANNOTATIONS_SNAPSHOT = snapshot;
        }
    }

    // ==================================================================================
    // Extension-annotation types
    // ==================================================================================

    /**
     * Builds the optional per-field metadata of a registered annotation, see
     * {@link #registerAnnotation(Class, AnnotationMetadataFactory)}.
     *
     * @param <A> the annotation type
     */
    @FunctionalInterface
    public interface AnnotationMetadataFactory<A extends Annotation> {

        /**
         * @param lookup     a private-access lookup for the class declaring the field, usable with
         *                   {@link ReflectUtil#createAdaptedMethodHandle}
         * @param definition the definition built for the annotated field, whose
         *                   {@link DataFieldDefinition#field} is the reflected field
         * @param type       the field's managed type (the {@code toManaged} type of a
         *                   {@code @Conversion} field)
         * @param annotation the annotation instance found on the field
         * @return the metadata to store in {@link AnnotatedField#metadata()}, or {@code null}
         */
        @Nullable
        Object create(MethodHandles.Lookup lookup, DataFieldDefinition<?> definition, Class<?> type, A annotation);
    }

    /**
     * One field carrying a registered extension annotation, paired with the definition the scan
     * built for that field and the metadata its {@link AnnotationMetadataFactory} produced, if the
     * annotation was registered with one.
     *
     * <p>The definition is the very instance the owning manager holds, so a consumer of the
     * annotation can read or write the field through
     * {@link DataFieldDefinition#get(Object)} / {@link DataFieldDefinition#set(Object, Object)},
     * and can recognise the field through {@link DataFieldDefinition#field}.</p>
     *
     * <p>The metadata is the user-defined parsed form of the annotation's attributes: fixed at
     * scan time, shared by every instance of the holder, and {@code null} when the annotation was
     * registered without a factory or the factory returned {@code null}. Its type is whatever the
     * factory returned — the library's own {@link SaveToItem @SaveToItem} yields a
     * {@link SaveToItemMetadata}.</p>
     *
     * @param definition the definition created for the annotated field
     * @param annotation the annotation instance found on that field
     * @param metadata   the metadata built by the registered factory, or {@code null}
     * @param <A>        the annotation type
     */
    public record AnnotatedField<A extends Annotation>(DataFieldDefinition<?> definition, A annotation, @Nullable Object metadata) {

        /**
         * @return the annotated field
         */
        public Field field() {
            return definition.field;
        }
    }

    /**
     * One registered annotation and its optional metadata factory, as read by a scan.
     */
    private record RegisteredAnnotation(Class<? extends Annotation> type, @Nullable AnnotationMetadataFactory<?> factory) {
    }

    /**
     * One annotation of a field that matched a registration.
     */
    private record MatchedAnnotation(Annotation annotation, @Nullable AnnotationMetadataFactory<?> factory) {
    }

    // ==================================================================================
    // Lookup API
    // ==================================================================================

    /**
     * Returns the scanned definition storage for a class, computing it on first request.
     *
     * <p>The class hierarchy is walked through superclasses (fields of every level are merged,
     * child classes may not reuse a parent's storage key), and the result is cached globally.
     * A class that declares no managed field inherits its parent's storage or shares a single
     * empty instance, so this never returns {@code null}.</p>
     *
     * @param clazz the holder class to scan
     * @return the (cached) definitions for that class
     */
    public static FieldDefinitionStorage get(Class<?> clazz) {
        return CACHE.get(clazz);
    }

    /**
     * Looks up a definition by field within this storage's type bucket.
     *
     * <p>The bucket is keyed by the field's declared type, so only fields of the same type are
     * compared. The comparison is {@link Field#equals(Object)} — declaring class, name and type —
     * not identity: {@link Class#getDeclaredField(String)} hands every caller its own reflection
     * copy, so an identity check would only ever match the instance the scan kept. This is the same
     * rule {@link FieldDataManager#getFieldDefinition(Field)} uses.</p>
     *
     * @param field the reflected field to find
     * @return the matching definition, or {@code null} if this class does not manage that field
     */
    public DataFieldDefinition<?> getFieldDefinition(Field field) {
        for (var definition : typeDefinitions.getOrDefault(field.getType(), Collections.emptyList())) {
            if (definition.field.equals(field)) return definition;
        }
        return null;
    }

    /**
     * Returns the fields of this class that carry {@code annotationType}, each paired with its
     * definition and metadata.
     *
     * <p>The array is the storage's own and is returned directly — no copy, no list wrapper — so a
     * lookup costs one map read. Treat it as read-only: it belongs to the cached storage and is
     * shared by every caller, so writing an element would corrupt the class metadata. An
     * unregistered or absent annotation yields a shared empty array.</p>
     *
     * <p>The annotation must have been registered with
     * {@link #registerAnnotation(Class, AnnotationMetadataFactory)} before this class was scanned.
     * Entries keep scan order: the class's own fields in declaration order, then the ones inherited
     * from its superclasses (the storage merges the parent's entries after its own).</p>
     *
     * @param annotationType a registered extension annotation
     * @param <A>            the annotation type
     * @return the annotated fields of this class as a read-only array, empty when there are none
     */
    @SuppressWarnings("unchecked")
    public <A extends Annotation> AnnotatedField<A>[] getAnnotatedFields(Class<A> annotationType) {
        var fields = annotatedFields.get(annotationType);
        return (AnnotatedField<A>[]) (fields == null ? NO_ANNOTATED_FIELDS : fields);
    }

    /**
     * @param annotationType a registered extension annotation
     * @return {@code true} if at least one field of this class carries {@code annotationType}
     */
    public boolean hasAnnotatedFields(Class<? extends Annotation> annotationType) {
        return annotatedFields.containsKey(annotationType);
    }

    /**
     * Returns just the definitions of the fields carrying {@code annotationType}, in the same order
     * {@link #getAnnotatedFields(Class)} gives them.
     *
     * <p>This is the array an annotation consumer that only needs the fields — the item payload of
     * {@link com.gto.datasynclib.blockentity.SaveToItem @SaveToItem}, for instance — should hold on
     * to: it is built once with the storage and is never rebuilt, so a per-write call allocates
     * nothing (the {@link AnnotatedField} array would have to be mapped every time).</p>
     *
     * @param annotationType a registered extension annotation
     * @param <A>            the annotation type
     * @return this class's definitions for that annotation as a read-only array, empty when there are none
     */
    public <A extends Annotation> DataFieldDefinition<?>[] getAnnotatedFieldDefinitions(Class<A> annotationType) {
        var definitions = annotatedDefinitions.get(annotationType);
        return definitions == null ? NO_ANNOTATED_DEFINITIONS : definitions;
    }

    // ==================================================================================
    // Instance state
    // ==================================================================================

    final Reference2ReferenceOpenHashMap<Class<?>, List<DataFieldDefinition<?>>> typeDefinitions = new Reference2ReferenceOpenHashMap<>();
    final HashMap<String, DataFieldDefinition<?>> definitionMap;
    final DataFieldDefinition<?>[] allDefinitions;
    final DataFieldDefinition<?>[] saveDefinitions;
    final DataFieldDefinition<?>[] syncToClientDefinitions;
    final DataFieldDefinition<?>[] syncToServerDefinitions;
    /**
     * The registered extension annotations present on this class's fields, as annotation type →
     * the fields carrying it, each paired with its definition and metadata. See
     * {@link #registerAnnotation(Class)} and {@link #getAnnotatedFields(Class)}.
     */
    final Reference2ReferenceOpenHashMap<Class<? extends Annotation>, AnnotatedField<?>[]> annotatedFields;

    /**
     * The definitions of {@link #annotatedFields}, in the same order, derived once with the storage
     * so a consumer that only needs the fields never maps the records: see
     * {@link #getAnnotatedFieldDefinitions(Class)}.
     */
    final Reference2ReferenceOpenHashMap<Class<? extends Annotation>, DataFieldDefinition<?>[]> annotatedDefinitions;

    /**
     * The registered extension annotation types present on this class's fields, as a read-only set
     * built once with the storage.
     */
    @Getter
    final Set<Class<? extends Annotation>> annotationTypes;

    private FieldDefinitionStorage() {
        definitionMap = new HashMap<>(0);
        allDefinitions = new DataFieldDefinition[0];
        saveDefinitions = new DataFieldDefinition[0];
        syncToClientDefinitions = new DataFieldDefinition[0];
        syncToServerDefinitions = new DataFieldDefinition[0];
        annotatedFields = new Reference2ReferenceOpenHashMap<>(0);
        annotatedDefinitions = new Reference2ReferenceOpenHashMap<>(0);
        annotationTypes = Collections.unmodifiableSet(annotatedFields.keySet());
    }

    private FieldDefinitionStorage(ArrayList<DataFieldDefinition<?>> fields, ArrayList<AnnotatedField<?>> annotatedFields) {
        fields.sort(DataFieldDefinition.COMPARATOR);
        definitionMap = new HashMap<>(fields.size());
        var saveList = new ArrayList<DataFieldDefinition<?>>();
        var syncToClientList = new ArrayList<DataFieldDefinition<?>>();
        var syncToServerList = new ArrayList<DataFieldDefinition<?>>();
        fields.forEach(d -> {
            if (definitionMap.put(d.key, d) != null)
                throw new RuntimeException("Duplicate sync field key: " + d.key + " in class " + d.field.getDeclaringClass().getName());
            typeDefinitions.computeIfAbsent(d.field.getType(), k -> new ArrayList<>()).add(d);
            if (d.isSave) saveList.add(d);
            if (d.isSyncToClient) syncToClientList.add(d);
            if (d.isSyncToServer) syncToServerList.add(d);
        });
        allDefinitions = fields.toArray(new DataFieldDefinition[0]);
        saveDefinitions = saveList.toArray(new DataFieldDefinition[0]);
        syncToClientDefinitions = syncToClientList.toArray(new DataFieldDefinition[0]);
        syncToServerDefinitions = syncToServerList.toArray(new DataFieldDefinition[0]);
        this.annotatedFields = groupAnnotatedFields(annotatedFields);
        this.annotatedDefinitions = groupAnnotatedDefinitions(this.annotatedFields);
        this.annotationTypes = Collections.unmodifiableSet(this.annotatedFields.keySet());
    }

    /**
     * Derives the definition-only view of {@code grouped}, once, so a consumer such as the item
     * payload reads the definitions without mapping {@link AnnotatedField} records every write.
     */
    private static Reference2ReferenceOpenHashMap<Class<? extends Annotation>, DataFieldDefinition<?>[]> groupAnnotatedDefinitions(
            Reference2ReferenceOpenHashMap<Class<? extends Annotation>, AnnotatedField<?>[]> grouped) {
        var result = new Reference2ReferenceOpenHashMap<Class<? extends Annotation>, DataFieldDefinition<?>[]>(grouped.size());
        for (var it = grouped.reference2ReferenceEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            var annotated = entry.getValue();
            var definitions = new DataFieldDefinition<?>[annotated.length];
            for (int i = 0; i < annotated.length; i++) definitions[i] = annotated[i].definition();
            result.put(entry.getKey(), definitions);
        }
        return result;
    }

    private static Reference2ReferenceOpenHashMap<Class<? extends Annotation>, AnnotatedField<?>[]> groupAnnotatedFields(List<AnnotatedField<?>> annotatedFields) {
        var grouped = new Reference2ReferenceOpenHashMap<Class<? extends Annotation>, ArrayList<AnnotatedField<?>>>();
        for (var annotatedField : annotatedFields)
            grouped.computeIfAbsent(annotatedField.annotation().annotationType(), k -> new ArrayList<>()).add(annotatedField);
        var result = new Reference2ReferenceOpenHashMap<Class<? extends Annotation>, AnnotatedField<?>[]>(grouped.size());
        for (var it = grouped.reference2ReferenceEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            result.put(entry.getKey(), entry.getValue().toArray(new AnnotatedField[0]));
        }
        return result;
    }

    // ==================================================================================
    // Scanning
    // ==================================================================================

    private static FieldDefinitionStorage of(Class<?> clazz) {
        var definitions = new ArrayList<DataFieldDefinition<?>>();
        var annotatedFields = new ArrayList<AnnotatedField<?>>();
        scanFields(clazz, definitions, annotatedFields, null);
        return new FieldDefinitionStorage(definitions, annotatedFields);
    }

    private static FieldDefinitionStorage of(Class<?> clazz, FieldDefinitionStorage storage) {
        var definitions = new ArrayList<DataFieldDefinition<?>>();
        var annotatedFields = new ArrayList<AnnotatedField<?>>();
        scanFields(clazz, definitions, annotatedFields, null);
        definitions.addAll(storage.definitionMap.values());
        for (var fields : storage.annotatedFields.values()) Collections.addAll(annotatedFields, fields);
        return new FieldDefinitionStorage(definitions, annotatedFields);
    }

    /**
     * Creates a composed getter function that chains through a nested
     * {@code @AdditionalHolder} field to reach the true owning object.
     *
     * <p>The returned function first applies the {@code parentSource} (if any) to
     * resolve the parent holder, then reads this field from that parent via a
     * {@link VarHandle}. This supports arbitrary-depth nesting.</p>
     *
     * @param lookup       the private-access lookup for the declaring class
     * @param field        the {@code @AdditionalHolder}-annotated field
     * @param parentSource the parent's source function chain, or {@code null} at top level
     * @return a composed function that resolves to the nested field's value
     */
    private static Function<Object, Object> createNestedSourceFunction(MethodHandles.Lookup lookup, Field field, @Nullable Function<Object, Object> parentSource) {
        var getter = ReflectUtil.createVarHandle(lookup, field);
        return o -> {
            try {
                return getter.get(parentSource == null ? o : parentSource.apply(o));
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Recursively scans a class and its superclasses for annotated fields, building
     * {@link DataFieldDefinition} instances.
     *
     * <h3>Scanning process:</h3>
     * <ol>
     *   <li>Obtains a {@link MethodHandles.Lookup} with private access to the target class</li>
     *   <li>Iterates declared fields, skipping statics</li>
     *   <li>For {@code @AdditionalHolder} fields, recursively scans the nested type
     *       with a composed source-accessor function chain</li>
     *   <li>For annotated fields ({@code @SaveToDisk}, {@code @SyncToClient},
     *       {@code @SyncToServer}, {@code @AddToManager}, or a registered extension
     *       annotation), resolves any {@code @Conversion} annotation:
     *       <ul>
     *         <li>Looks up the static {@code toManaged} field (required)</li>
     *         <li>Infers the managed type from the function's second generic parameter</li>
     *         <li>Optionally looks up the static {@code toField} field (silently
     *             skipped if absent — common for final/access-mode fields)</li>
     *       </ul>
     *   </li>
     *   <li>Constructs a {@link DataFieldDefinition} via the appropriate factory path
     *       (access-mode, custom codec, generic, or standard)</li>
     *   <li>Records the field for every registered extension annotation it carries, as an
     *       {@link AnnotatedField} under the annotation type, including the metadata its
     *       {@link AnnotationMetadataFactory} produces</li>
     * </ol>
     *
     * @param clazz           the class to scan
     * @param definitions     the list to append discovered definitions to
     * @param annotatedFields the list to append extension-annotation records to
     * @param source          a function chain resolving the owning object from the root holder,
     *                        or {@code null} for top-level fields (where the holder IS the owner)
     */
    private static void scanFields(Class<?> clazz, ArrayList<DataFieldDefinition<?>> definitions, ArrayList<AnnotatedField<?>> annotatedFields, @Nullable Function<Object, Object> source) {
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(clazz, LOOKUP);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        for (var field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            var type = field.getType();
            var additionalHolder = field.getAnnotation(AdditionalHolder.class);
            boolean childManager = additionalHolder != null && additionalHolder.childManager();
            if (additionalHolder != null) {
                field.setAccessible(true);
                // childManager mode: the field's object owns a dedicated sub-manager; do NOT
                // flatten its annotated fields into the parent. A single field definition
                // (routed to ChildManagerAccess) is created below instead.
                if (!childManager) {
                    scanFields(type, definitions, annotatedFields, createNestedSourceFunction(lookup, field, source));
                }
            }
            var saveToDisk = field.getAnnotation(SaveToDisk.class);
            var syncToClient = field.getAnnotation(SyncToClient.class);
            var syncToServer = field.getAnnotation(SyncToServer.class);
            // A registered extension annotation manages its field on its own, exactly like
            // @AddToManager: the annotation is the field's reason to have a definition, and that
            // definition is what the annotation is recorded with.
            var extensionAnnotations = getExtensionAnnotations(field);
            if (saveToDisk == null && syncToClient == null && syncToServer == null
                    && extensionAnnotations.length == 0 && field.getAnnotation(AddToManager.class) == null)
                continue;
            if (childManager) {
                // Child-manager field: serialized as a single field via its own sub-manager.
                var definition = createChildManagerFieldDefinition(clazz, lookup, field, type, source, saveToDisk, syncToClient, syncToServer);
                definitions.add(definition);
                recordExtensionAnnotations(lookup, annotatedFields, definition, type, extensionAnnotations);
                continue;
            }
            var conversion = field.getAnnotation(Conversion.class);
            Field conversionField = null;
            Function conversionGetFunction = null;
            Function conversionSetFunction = null;
            if (conversion != null) {
                try {
                    // Resolve the forward conversion function: static Function<FieldType, ManagedType>
                    conversionField = clazz.getDeclaredField(conversion.toManaged());
                    conversionField.setAccessible(true);
                    conversionGetFunction = (Function) conversionField.get(null);
                    // The managed type is the return type (2nd generic param) of the Function
                    type = ReflectUtil.getResolvedGenericArguments(conversionField.getGenericType(), Function.class)[1];
                    try {
                        // Resolve the reverse conversion function: static Function<ManagedType, FieldType>
                        // This is optional — if absent, the setter path won't use conversion
                        var f = clazz.getDeclaredField(conversion.toField());
                        f.setAccessible(true);
                        conversionSetFunction = (Function) f.get(null);
                    } catch (NoSuchFieldException ignored) {
                        // toField is optional; silently skip for final/access-mode fields
                    }
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
            var definition = createFieldDefinition(lookup, field, type, source, new FieldAnnotationMetadata(clazz, field, type, saveToDisk, syncToClient, syncToServer), conversionField, conversionGetFunction, conversionSetFunction);
            if (definition != null) {
                definitions.add(definition);
                recordExtensionAnnotations(lookup, annotatedFields, definition, type, extensionAnnotations);
            }
        }
    }

    /**
     * The registered extension annotations present on {@code field}, in registration order.
     *
     * <p>Reads the registration snapshot once, so a concurrent registration neither blocks the
     * scan nor lets it see a half-added entry. The empty result is a shared array, and the
     * probing array is only allocated once a first annotation matches.</p>
     *
     * @param field the field being scanned
     * @return the annotations found with their factories, or a shared empty array when there are none
     */
    private static MatchedAnnotation[] getExtensionAnnotations(Field field) {
        var registered = EXTENSION_ANNOTATIONS_SNAPSHOT;
        if (registered.length == 0) return NO_EXTENSION_ANNOTATIONS;
        MatchedAnnotation[] found = null;
        var count = 0;
        for (var registration : registered) {
            var annotation = field.getAnnotation(registration.type());
            if (annotation == null) continue;
            if (found == null) found = new MatchedAnnotation[registered.length];
            found[count++] = new MatchedAnnotation(annotation, registration.factory());
        }
        if (found == null) return NO_EXTENSION_ANNOTATIONS;
        return count == found.length ? found : Arrays.copyOf(found, count);
    }

    /**
     * Records one {@link AnnotatedField} per matched extension annotation, asking the annotation's
     * metadata factory for its metadata first (if it has one).
     */
    @SuppressWarnings("unchecked")
    private static void recordExtensionAnnotations(MethodHandles.Lookup lookup, ArrayList<AnnotatedField<?>> annotatedFields,
                                                   DataFieldDefinition<?> definition, Class<?> type, MatchedAnnotation[] annotations) {
        for (var matched : annotations) {
            var factory = (AnnotationMetadataFactory<Annotation>) matched.factory();
            Object metadata = null;
            if (factory != null) {
                try {
                    metadata = factory.create(lookup, definition, type, matched.annotation());
                } catch (RuntimeException e) {
                    // the factory runs during the one-time scan: name the annotation and field, or
                    // the failure surfaces as a bare stack trace out of a class initializer
                    throw new RuntimeException("Failed to build "
                            + matched.annotation().annotationType().getName() + " metadata for field "
                            + ReflectUtil.getFieldDetailedName(definition.field), e);
                }
            }
            annotatedFields.add(new AnnotatedField<>(definition, matched.annotation(), metadata));
        }
    }

    /**
     * Builds a {@link DataFieldDefinition} for an {@code @AdditionalHolder(childManager = true)}
     * field. Unlike flattened {@code @AdditionalHolder} fields, whose inner annotations are
     * hoisted into the parent manager, a child-manager field is serialized as a single unit:
     * its object owns a dedicated {@link FieldDataManager} (via
     * {@link ChildFieldDataHolder}), managed through {@link ChildManagerAccess
     * ChildManagerAccess}.
     *
     * <p>Persistence and synchronization of the whole sub-object follow the annotations placed
     * on the field itself ({@code @SaveToDisk}, {@code @SyncToClient}, {@code @SyncToServer}).</p>
     *
     * @param lookup       private-access lookup for the declaring class
     * @param field        the {@code @AdditionalHolder(childManager = true)} annotated field
     * @param type         the field's type (the sub-object's class)
     * @param source       parent source chain, or {@code null} at top level
     * @param saveToDisk   the field's {@code @SaveToDisk} annotation (may be {@code null})
     * @param syncToClient the field's {@code @SyncToClient} annotation (may be {@code null})
     * @param syncToServer the field's {@code @SyncToServer} annotation (may be {@code null})
     * @return a child-manager {@link DataFieldDefinition}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataFieldDefinition<?> createChildManagerFieldDefinition(Class<?> clazz, MethodHandles.Lookup lookup, Field field, Class<?> type, @Nullable Function<Object, Object> source, @Nullable SaveToDisk saveToDisk, @Nullable SyncToClient syncToClient, @Nullable SyncToServer syncToServer) {
        try {
            field.setAccessible(true);
            boolean isFinal = Modifier.isFinal(field.getModifiers());
            var annotations = new FieldAnnotationMetadata(clazz, field, type, saveToDisk, syncToClient, syncToServer);
            return new DataFieldDefinition<>(
                    lookup, field, type, ChildManagerAccess::new,
                    source, annotations, new Class[0],
                    isFinal, annotations.instanceAsValue(), STRATEGIES,
                    null, null);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create child-manager definition for field: " + ReflectUtil.getFieldDetailedName(field), e);
        }
    }

    /**
     * Resolves the field to the definition for its declared mode: access ({@code @Access} or a
     * {@code final} field), a custom {@code @Codec}, {@code @Generic}, or the value chain.
     */
    private static DataFieldDefinition<?> createFieldDefinition(MethodHandles.Lookup lookup, Field field, Class<?> type, @Nullable Function<Object, Object> source, FieldAnnotationMetadata annotations, Field conversionField, Function conversionGetFunction, Function conversionSetFunction) {
        try {
            Class<?>[] genericType;
            field.setAccessible(true);
            try {
                if (conversionField != null) {
                    genericType = ReflectUtil.getFieldGenericTypeClasses(ReflectUtil.getFieldGenericType(conversionField.getGenericType())[1]);
                } else {
                    genericType = ReflectUtil.getFieldGenericTypeClasses(field.getGenericType());
                }
            } catch (Throwable e) {
                genericType = new Class<?>[0];
            }
            boolean isFinal = Modifier.isFinal(field.getModifiers());
            if (isFinal && type.isPrimitive())
                throw new IllegalStateException("Field is isPrimitive not access");
            if (annotations.hasAccessAnnotation() || isFinal) {
                return createAccessFieldDefinition(lookup, field, type, source, annotations, genericType, isFinal, conversionGetFunction, conversionSetFunction);
            } else if (annotations.hasCustomCodec()) {
                return new DataFieldDefinition<>(lookup, field, type, ObjCodecField::new, source, annotations, genericType, false, true, STRATEGIES, conversionGetFunction, conversionSetFunction);
            } else if (annotations.hasGeneric()) {
                if (genericType.length == 0)
                    throw new IllegalStateException("Field is annotated with @Generic, but it has no generic type");
                return createGenericFieldDefinition(lookup, field, type, source, annotations, genericType, false, conversionGetFunction, conversionSetFunction);
            } else {
                return createFieldDefinition(lookup, field, type, source, annotations, genericType, false, conversionGetFunction, conversionSetFunction);
            }
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create definition for field: " + ReflectUtil.getFieldDetailedName(field), e);
        }
    }

    private static DataFieldDefinition<?> createAccessFieldDefinition(MethodHandles.Lookup lookup, Field field, Class<?> type, @Nullable Function<Object, Object> source, FieldAnnotationMetadata annotations, Class<?>[] genericType, boolean isFinal, Function conversionGetFunction, Function conversionSetFunction) {
        var factory = ACCESS_CACHE.get(type);
        return new DataFieldDefinition<>(lookup, field, type, factory, source, annotations, genericType, isFinal, annotations.instanceAsValue(), STRATEGIES, conversionGetFunction, conversionSetFunction);
    }

    private static DataFieldDefinition<?> createGenericFieldDefinition(MethodHandles.Lookup lookup, Field field, Class<?> type, @Nullable Function<Object, Object> source, FieldAnnotationMetadata annotations, Class<?>[] genericType, boolean isFinal, Function conversionGetFunction, Function conversionSetFunction) {
        var factory = GENERIC_FIELDS_CACHE.get(type).getCache(HashUtil.arrayIdentityWrapper(genericType));
        return new DataFieldDefinition<>(lookup, field, type, factory, source, annotations, genericType, isFinal, true, STRATEGIES, conversionGetFunction, conversionSetFunction);
    }

    /**
     * Resolves a field to a factory with a three-tier fallback strategy:
     * <ol>
     *   <li><strong>Standard factory</strong> — lookup in {@link #FIELDS_CACHE} by exact type.
     *       This covers pre-registered types (primitives, objects with known codecs)</li>
     *   <li><strong>Generic factory</strong> — if the type has generic parameters and a
     *       matching {@code CustomGenericFactory} is registered. Falls through to step 3
     *       if no generic params exist</li>
     *   <li><strong>Access factory</strong> — lookup in {@link #ACCESS_CACHE} as a last resort.
     *       This handles containers, IFieldDataHolder types, and other access-mode fields</li>
     * </ol>
     *
     * <p>The fallback chain exists because field types are not always known at registration
     * time — custom types, parameterized generics, and interface implementations often need
     * to match against a predicate rather than an exact class.</p>
     */
    private static DataFieldDefinition<?> createFieldDefinition(MethodHandles.Lookup lookup, Field field, Class<?> type, @Nullable Function<Object, Object> source, FieldAnnotationMetadata annotations, Class<?>[] genericType, boolean isFinal, Function conversionGetFunction, Function conversionSetFunction) {
        try {
            // Tier 1: try standard (exact-type) factory
            var factory = FIELDS_CACHE.get(type);
            return new DataFieldDefinition<>(lookup, field, type, factory, source, annotations, genericType, isFinal, true, STRATEGIES, conversionGetFunction, conversionSetFunction);
        } catch (Throwable e) {
            try {
                // Tier 2: try generic factory if type parameters exist
                if (genericType.length > 0) {
                    return createGenericFieldDefinition(lookup, field, type, source, annotations, genericType, isFinal, conversionGetFunction, conversionSetFunction);
                }
                throw e;
            } catch (Throwable e2) {
                // Tier 3: fall back to access-mode factory
                return createAccessFieldDefinition(lookup, field, type, source, annotations, genericType, isFinal, conversionGetFunction, conversionSetFunction);
            }
        }
    }
}
