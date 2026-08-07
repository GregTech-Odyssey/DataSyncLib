package com.gto.datasynclib;

import com.gto.datasynclib.annotations.*;
import com.gto.datasynclib.field.access.ChildManagerAccess;
import com.gto.datasynclib.field.object.ObjCodecField;
import com.gto.datasynclib.util.HashUtil;
import com.gto.datasynclib.util.ReflectUtil;
import com.gto.datasynclib.util.cache.HashMapCache;
import com.gto.datasynclib.util.cache.IdentityHashMapCache;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

/**
 * Central registry and cache for {@link DataFieldDefinition} instances discovered from annotated classes.
 *
 * <p>Scans class fields for {@code @SaveToDisk}, {@code @SyncToClient}, and {@code @SyncToServer} annotations,
 * constructing {@link DataFieldDefinition} entries that drive the field synchronization and persistence
 * lifecycle managed by {@link FieldDataManager}.</p>
 *
 * <p>Supports a pluggable factory system for field types ({@link #registerFactory}),
 * access types ({@link #registerAccessFactory}), and generic types ({@link #registerGenericFactory}),
 * allowing custom {@link DataField} implementations for different field categories.</p>
 *
 * <p>Strategy registration ({@link #registerStrategy}) allows custom hash/equality functions
 * for change detection on complex types.</p>
 */
public final class FieldDefinitionStorage {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

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

    private static final IdentityHashMapCache<Class<?>, HashMapCache<Supplier<Class<?>[]>, DataField.Factory<?>>> GENERIC_FIELDS_CACHE = new IdentityHashMapCache<>(type -> new HashMapCache<>(genericFunction(type)));

    private static final ArrayList<DataField.CustomFactory<?>> FIELDS = new ArrayList<>();
    private static final IdentityHashMapCache<Class<?>, DataField.Factory<?>> FIELDS_CACHE = new IdentityHashMapCache<>(type -> {
        for (var f : FIELDS) {
            if (f.predicate().test(type)) {
                return f.factory().apply(type);
            }
        }
        throw new IllegalStateException("No factory for " + type);
    });

    private static final ArrayList<DataField.CustomFactory<?>> ACCESS = new ArrayList<>();
    private static final IdentityHashMapCache<Class<?>, DataField.Factory<?>> ACCESS_CACHE = new IdentityHashMapCache<>(type -> {
        for (var f : ACCESS) {
            if (f.predicate().test(type)) {
                return f.factory().apply(type);
            }
        }
        throw new IllegalStateException("No factory for " + type);
    });

    private static final Reference2ReferenceOpenHashMap<Class<?>, Hash.Strategy<?>> STRATEGIES = new Reference2ReferenceOpenHashMap<>();

    private static final ConcurrentHashMap<Class<?>, FieldDefinitionStorage> CACHE = new ConcurrentHashMap<>();

    private static final FieldDefinitionStorage EMPTY = new FieldDefinitionStorage();

    public static <T> void registerAccessFactory(Class<T> type, DataField.Factory<T> factory) {
        synchronized (ACCESS_CACHE) {
            ACCESS_CACHE.put(type, factory);
        }
    }

    public static <T> void registerAccessInterfaceFactory(Class<T> interfaceType, Function<Class<?>, DataField.Factory<T>> factory, int priority) {
        registerAccessCustomFactory(interfaceType::isAssignableFrom, factory, priority);
    }

    public static <T> void registerAccessCustomFactory(Predicate<Class<?>> type, Function<Class<?>, DataField.Factory<T>> factory, int priority) {
        synchronized (ACCESS) {
            ACCESS.add(new DataField.CustomFactory<>(type, factory, priority));
            ACCESS.sort(DataField.CustomFactory.COMPARATOR);
        }
    }

    public static <T> void registerGenericFactory(Class<?> type, DataField.Factory<T> factory, Class<?>... genericType) {
        synchronized (GENERIC_FIELDS_CACHE) {
            GENERIC_FIELDS_CACHE.computeIfAbsent(type, k -> new HashMapCache<>(genericFunction(type))).put(HashUtil.arrayIdentityWrapper(genericType), factory);
        }
    }

    public static <T> void registerCustomGenericFactory(BiPredicate<Class<?>, Class<?>[]> type, BiFunction<Class<?>, Class<?>[], DataField.Factory<T>> factory, int priority) {
        synchronized (GENERIC_FIELDS) {
            GENERIC_FIELDS.add(new DataField.CustomGenericFactory<>(type, factory, priority));
            GENERIC_FIELDS.sort(DataField.CustomGenericFactory.COMPARATOR);
        }
    }

    public static <T> void registerFactory(Class<T> type, DataField.Factory<T> factory) {
        synchronized (FIELDS_CACHE) {
            FIELDS_CACHE.put(type, factory);
        }
    }

    public static <T> void registerInterfaceFactory(Class<T> interfaceType, Function<Class<?>, DataField.Factory<T>> factory, int priority) {
        registerCustomFactory(interfaceType::isAssignableFrom, factory, priority);
    }

    public static <T> void registerCustomFactory(Predicate<Class<?>> type, Function<Class<?>, DataField.Factory<T>> factory, int priority) {
        synchronized (FIELDS) {
            FIELDS.add(new DataField.CustomFactory<>(type, factory, priority));
            FIELDS.sort(DataField.CustomFactory.COMPARATOR);
        }
    }

    public static <T> void registerStrategy(Class<T> type, Hash.Strategy<T> strategy) {
        synchronized (STRATEGIES) {
            STRATEGIES.put(type, strategy);
        }
    }

    final Reference2ReferenceOpenHashMap<Class<?>, List<DataFieldDefinition<?>>> typeDefinitions = new Reference2ReferenceOpenHashMap<>();
    final LinkedHashMap<String, DataFieldDefinition<?>> definitionMap;
    final DataFieldDefinition<?>[] allDefinitions;
    final DataFieldDefinition<?>[] saveDefinitions;
    final DataFieldDefinition<?>[] syncToClientDefinitions;
    final DataFieldDefinition<?>[] syncToServerDefinitions;

    private FieldDefinitionStorage(ArrayList<DataFieldDefinition<?>> fields) {
        fields.sort(DataFieldDefinition.COMPARATOR);
        definitionMap = new LinkedHashMap<>(fields.size());
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
    }

    public DataFieldDefinition<?> getFieldDefinition(Field field) {
        for (var definition : typeDefinitions.getOrDefault(field.getType(), Collections.emptyList())) {
            if (definition.field == field) return definition;
        }
        return null;
    }

    private FieldDefinitionStorage() {
        definitionMap = new LinkedHashMap<>(0);
        allDefinitions = new DataFieldDefinition[0];
        saveDefinitions = new DataFieldDefinition[0];
        syncToClientDefinitions = new DataFieldDefinition[0];
        syncToServerDefinitions = new DataFieldDefinition[0];
    }

    private static FieldDefinitionStorage of(Class<?> clazz) {
        var definitions = new ArrayList<DataFieldDefinition<?>>();
        scanFields(clazz, definitions, null);
        return new FieldDefinitionStorage(definitions);
    }

    private static FieldDefinitionStorage of(Class<?> clazz, FieldDefinitionStorage storage) {
        var definitions = new ArrayList<DataFieldDefinition<?>>();
        scanFields(clazz, definitions, null);
        definitions.addAll(storage.definitionMap.values());
        return new FieldDefinitionStorage(definitions);
    }

    public static FieldDefinitionStorage get(Class<?> clazz) {
        var storage = CACHE.get(clazz);
        if (storage != null) return storage;
        synchronized (CACHE) {
            Class<?> sc = clazz.getSuperclass();
            if (sc != null && sc != Object.class) {
                var sh = get(sc);
                storage = of(clazz, sh);
                if (storage.allDefinitions.length == sh.allDefinitions.length) {
                    storage = sh;
                }
            }
            if (storage == null) {
                storage = of(clazz);
                if (storage.allDefinitions.length == 0) storage = EMPTY;
            }
            CACHE.put(clazz, storage);
        }
        return storage;
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
     *   <li>Obtains a {@link java.lang.invoke.MethodHandles.Lookup} with private access
     *       to the target class</li>
     *   <li>Iterates declared fields, skipping statics</li>
     *   <li>For {@code @AdditionalHolder} fields, recursively scans the nested type
     *       with a composed source-accessor function chain</li>
     *   <li>For annotated fields ({@code @SaveToDisk}, {@code @SyncToClient},
     *       {@code @SyncToServer}, or {@code @AddToManager}), resolves any
     *       {@code @Conversion} annotation:
     *       <ul>
     *         <li>Looks up the static {@code getFunction} field (required)</li>
     *         <li>Infers the managed type from the function's second generic parameter</li>
     *         <li>Optionally looks up the static {@code setFunction} field (silently
     *             skipped if absent — common for final/access-mode fields)</li>
     *       </ul>
     *   </li>
     *   <li>Constructs a {@link DataFieldDefinition} via the appropriate factory path
     *       (access-mode, custom codec, generic, or standard)</li>
     * </ol>
     *
     * @param clazz       the class to scan
     * @param definitions the list to append discovered definitions to
     * @param source      a function chain resolving the owning object from the root holder,
     *                    or {@code null} for top-level fields (where the holder IS the owner)
     */
    private static void scanFields(Class<?> clazz, ArrayList<DataFieldDefinition<?>> definitions, @Nullable Function<Object, Object> source) {
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
                    scanFields(type, definitions, createNestedSourceFunction(lookup, field, source));
                }
            }
            var savetoDisk = field.getAnnotation(SaveToDisk.class);
            var syncToClient = field.getAnnotation(SyncToClient.class);
            var syncToServer = field.getAnnotation(SyncToServer.class);
            if (savetoDisk == null && syncToClient == null && syncToServer == null && field.getAnnotation(AddToManager.class) == null)
                continue;
            if (childManager) {
                // Child-manager field: serialized as a single field via its own sub-manager.
                var definition = createChildManagerFieldDefinition(clazz, lookup, field, type, source, savetoDisk, syncToClient, syncToServer);
                definitions.add(definition);
                continue;
            }
            var conversion = field.getAnnotation(Conversion.class);
            Field conversionField = null;
            Function conversionGetFunction = null;
            Function conversionSetFunction = null;
            if (conversion != null) {
                try {
                    // Resolve the forward conversion function: static Function<FieldType, ManagedType>
                    conversionField = clazz.getDeclaredField(conversion.getFunction());
                    conversionField.setAccessible(true);
                    conversionGetFunction = (Function) conversionField.get(null);
                    // The managed type is the return type (2nd generic param) of the Function
                    type = ReflectUtil.getResolvedGenericArguments(conversionField.getGenericType(), Function.class)[1];
                    try {
                        // Resolve the reverse conversion function: static Function<ManagedType, FieldType>
                        // This is optional — if absent, the setter path won't use conversion
                        var f = clazz.getDeclaredField(conversion.setFunction());
                        f.setAccessible(true);
                        conversionSetFunction = (Function) f.get(null);
                    } catch (NoSuchFieldException ignored) {
                        // setFunction is optional; silently skip for final/access-mode fields
                    }
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
            var definition = createFieldDefinition(lookup, field, type, source, new FieldAnnotationMetadata(clazz, field, type, savetoDisk, syncToClient, syncToServer), conversionField, conversionGetFunction, conversionSetFunction);
            if (definition != null) definitions.add(definition);
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
     * @param savetoDisk   the field's {@code @SaveToDisk} annotation (may be {@code null})
     * @param syncToClient the field's {@code @SyncToClient} annotation (may be {@code null})
     * @param syncToServer the field's {@code @SyncToServer} annotation (may be {@code null})
     * @return a child-manager {@link DataFieldDefinition}, or {@code null} if none of the
     * persistence/sync annotations are present (nothing for the parent to manage)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataFieldDefinition<?> createChildManagerFieldDefinition(Class<?> clazz, MethodHandles.Lookup lookup, Field field, Class<?> type, @Nullable Function<Object, Object> source, @Nullable SaveToDisk savetoDisk, @Nullable SyncToClient syncToClient, @Nullable SyncToServer syncToServer) {
        try {
            field.setAccessible(true);
            boolean isFinal = Modifier.isFinal(field.getModifiers());
            var annotations = new FieldAnnotationMetadata(clazz, field, type, savetoDisk, syncToClient, syncToServer);
            return new DataFieldDefinition<>(
                    lookup, field, type, ChildManagerAccess::new,
                    source, annotations, new Class[0],
                    isFinal, annotations.createAccessInstance(), STRATEGIES,
                    null, null);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create child-manager definition for field: " + ReflectUtil.getFieldDetailedName(field), e);
        }
    }

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
        var factory = ACCESS_CACHE.getCache(type);
        return new DataFieldDefinition<>(lookup, field, type, factory, source, annotations, genericType, isFinal, annotations.createAccessInstance(), STRATEGIES, conversionGetFunction, conversionSetFunction);
    }

    private static DataFieldDefinition<?> createGenericFieldDefinition(MethodHandles.Lookup lookup, Field field, Class<?> type, @Nullable Function<Object, Object> source, FieldAnnotationMetadata annotations, Class<?>[] genericType, boolean isFinal, Function conversionGetFunction, Function conversionSetFunction) {
        var factory = GENERIC_FIELDS_CACHE.getCache(type).getCache(HashUtil.arrayIdentityWrapper(genericType));
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
            var factory = FIELDS_CACHE.getCache(type);
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
