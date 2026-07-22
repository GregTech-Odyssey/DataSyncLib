package com.gto.datasynclib;

import com.gto.datasynclib.annotations.*;
import com.gto.datasynclib.field.object.ObjCodecField;
import com.gto.datasynclib.util.HashUtil;
import com.gto.datasynclib.util.ReflectUtil;
import com.gto.datasynclib.util.cache.HashMapCache;
import com.gto.datasynclib.util.cache.IdentityHashMapCache;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import org.jetbrains.annotations.Nullable;

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

    private static Function<Object, Object> createNestedSourceFunction(Field field, @Nullable Function<Object, Object> parentSource) {
        var getter = ReflectUtil.createAdaptedGetter(field);
        return o -> {
            try {
                return getter.invokeExact(parentSource == null ? o : parentSource.apply(o));
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static void scanFields(Class<?> clazz, ArrayList<DataFieldDefinition<?>> definitions, @Nullable Function<Object, Object> source) {
        for (var field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.isAnnotationPresent(AdditionalHolder.class)) {
                field.setAccessible(true);
                scanFields(field.getType(), definitions, createNestedSourceFunction(field, source));
            }
            var savetoDisk = field.getAnnotation(SaveToDisk.class);
            var syncToClient = field.getAnnotation(SyncToClient.class);
            var syncToServer = field.getAnnotation(SyncToServer.class);
            if (savetoDisk == null && syncToClient == null && syncToServer == null && field.getAnnotation(AddToManager.class) == null)
                continue;
            var definition = createFieldDefinition(field, source, new FieldAnnotationMetadata(clazz, field, savetoDisk, syncToClient, syncToServer));
            if (definition != null) definitions.add(definition);
        }
    }

    private static DataFieldDefinition<?> createFieldDefinition(Field field, @Nullable Function<Object, Object> source, FieldAnnotationMetadata annotations) {
        try {
            Class<?>[] genericType;
            field.setAccessible(true);
            try {
                genericType = ReflectUtil.getFieldGenericTypeClasses(field.getGenericType());
            } catch (Throwable e) {
                genericType = new Class<?>[0];
            }
            boolean isFinal = Modifier.isFinal(field.getModifiers());
            if (isFinal && field.getType().isPrimitive())
                throw new IllegalStateException("Field is isPrimitive not access");
            if (annotations.hasAccessAnnotation() || isFinal) {
                return createAccessFieldDefinition(field, source, annotations, genericType, isFinal);
            } else if (annotations.hasCustomCodec()) {
                return new DataFieldDefinition<>(field, ObjCodecField::new, source, annotations, genericType, false, true, STRATEGIES);
            } else if (annotations.hasGeneric()) {
                if (genericType.length == 0)
                    throw new IllegalStateException("Field is annotated with @Generic, but it has no generic type");
                return createGenericFieldDefinition(field, source, annotations, genericType, false);
            } else {
                return createFieldDefinition(field, source, annotations, genericType, false);
            }
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create definition for field: " + ReflectUtil.getFieldDetailedName(field), e);
        }
    }

    private static DataFieldDefinition<?> createAccessFieldDefinition(Field field, @Nullable Function<Object, Object> source, FieldAnnotationMetadata annotations, Class<?>[] genericType, boolean isFinal) {
        var factory = ACCESS_CACHE.getCache(field.getType());
        return new DataFieldDefinition<>(field, factory, source, annotations, genericType, isFinal, annotations.createAccessInstance(), STRATEGIES);
    }

    private static DataFieldDefinition<?> createGenericFieldDefinition(Field field, @Nullable Function<Object, Object> source, FieldAnnotationMetadata annotations, Class<?>[] genericType, boolean isFinal) {
        var factory = GENERIC_FIELDS_CACHE.getCache(field.getType()).getCache(HashUtil.arrayIdentityWrapper(genericType));
        return new DataFieldDefinition<>(field, factory, source, annotations, genericType, isFinal, true, STRATEGIES);
    }

    private static DataFieldDefinition<?> createFieldDefinition(Field field, @Nullable Function<Object, Object> source, FieldAnnotationMetadata annotations, Class<?>[] genericType, boolean isFinal) {
        var type = field.getType();
        try {
            var factory = FIELDS_CACHE.getCache(type);
            return new DataFieldDefinition<>(field, factory, source, annotations, genericType, isFinal, true, STRATEGIES);
        } catch (Throwable e) {
            try {
                if (genericType.length > 0) {
                    return createGenericFieldDefinition(field, source, annotations, genericType, isFinal);
                }
                throw e;
            } catch (Throwable e2) {
                return createAccessFieldDefinition(field, source, annotations, genericType, isFinal);
            }
        }
    }
}
