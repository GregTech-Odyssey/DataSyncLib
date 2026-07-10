package com.gto.datasynclib.datastream;

import com.gto.datasynclib.DataSyncCodec;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.bytes.*;
import it.unimi.dsi.fastutil.chars.*;
import it.unimi.dsi.fastutil.doubles.*;
import it.unimi.dsi.fastutil.floats.*;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.*;
import it.unimi.dsi.fastutil.shorts.*;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An identity-keyed entry in a {@link DataComponentRegistry}, combining a name, an optional
 * {@link DataSyncCodec}, and merge/create behavior for use with {@link DataComponentMap}.
 *
 * <h3>Identity-based hashing</h3>
 * The {@link #mixCode} field is computed once from {@code System.identityHashCode(this)} mixed via
 * {@link HashCommon#mix}. This enables identity-based key lookup in {@link AbstractDataComponentMap},
 * ensuring that keys with the same name but from different registries are treated as distinct.
 *
 * <h3>Merge and Create Functions</h3>
 * <ul>
 *   <li>{@code mergeFunction(T oldValue, T newValue) -> T merged} — called when merging two
 *       component maps; defines how old and new values combine (e.g., collection union, map putAll).</li>
 *   <li>{@code createFunction(T value) -> T storedCopy} — called to potentially transform a value
 *       before storing (e.g., creating a defensive copy of a collection).</li>
 * </ul>
 * If both are {@code null}, the default behavior is "new replaces old" (standard put semantics).
 *
 * <p>The class provides {@link #create(String, Consumer)} with a {@link Builder} for fluent construction,
 * and numerous static builder factory methods ({@code int2IntMapBuilder}, {@code collectionBuilder}, etc.)
 * that pre-configure merge/create behavior for common FastUtil collection and map types.
 *
 * <p>Use {@link #createNoCodec(String)} for keys that participate in merge semantics but are never
 * directly serialized.
 *
 * @param <T> the type of values associated with this key
 * @see DataComponentRegistry
 * @see DataComponentMap
 */
@SuppressWarnings("unused")
public class DataComponentKey<T> {

    public final String name;
    public final DataSyncCodec<T> codec;
    public final int mixCode = HashCommon.mix(System.identityHashCode(this));
    public final BiFunction<T, T, T> mergeFunction;
    public final Function<T, T> createFunction;

    protected DataComponentKey(String name, DataSyncCodec<T> codec) {
        this.name = name;
        this.codec = codec;
        this.mergeFunction = (o, n) -> n;
        this.createFunction = n -> n;
    }

    protected DataComponentKey(String name, DataSyncCodec<T> codec, BiFunction<T, T, T> mergeFunction, Function<T, T> createFunction) {
        this.name = name;
        this.codec = codec;
        this.mergeFunction = mergeFunction;
        this.createFunction = createFunction;
    }

    public static <T> DataComponentKey<T> createNoCodec(String name) {
        return new DataComponentKey<>(name, null);
    }

    public static <T> DataComponentKey<T> create(String name, Consumer<Builder<T>> builder) {
        var b = new Builder<T>(name);
        builder.accept(b);
        return b.build();
    }

    public static <T, C extends Collection<T>> Consumer<Builder<C>> collectionBuilder(Function<C, C> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            o.addAll(n);
            return o;
        });
    }

    public static <K, V, T extends Map<K, V>> Consumer<Builder<T>> mapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            o.putAll(n);
            return o;
        });
    }

    // === Int-based maps and sets ===

    public static <T extends IntSet> Consumer<Builder<T>> intSetBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            o.addAll(n);
            return o;
        });
    }

    public static <T extends Int2IntMap> Consumer<Builder<T>> int2IntMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Int2IntMaps.fastForEach(n, e -> o.put(e.getIntKey(), e.getIntValue()));
            return o;
        });
    }

    public static <T extends Int2LongMap> Consumer<Builder<T>> int2LongMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Int2LongMaps.fastForEach(n, e -> o.put(e.getIntKey(), e.getLongValue()));
            return o;
        });
    }

    public static <T extends Int2ObjectMap<V>, V> Consumer<Builder<T>> int2ObjectMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Int2ObjectMaps.fastForEach(n, e -> o.put(e.getIntKey(), e.getValue()));
            return o;
        });
    }

    public static <T extends Int2FloatMap> Consumer<Builder<T>> int2FloatMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Int2FloatMaps.fastForEach(n, e -> o.put(e.getIntKey(), e.getFloatValue()));
            return o;
        });
    }

    public static <T extends Int2DoubleMap> Consumer<Builder<T>> int2DoubleMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Int2DoubleMaps.fastForEach(n, e -> o.put(e.getIntKey(), e.getDoubleValue()));
            return o;
        });
    }

    public static <T extends Int2BooleanMap> Consumer<Builder<T>> int2BooleanMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Int2BooleanMaps.fastForEach(n, e -> o.put(e.getIntKey(), e.getBooleanValue()));
            return o;
        });
    }

    public static <T extends Int2CharMap> Consumer<Builder<T>> int2CharMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Int2CharMaps.fastForEach(n, e -> o.put(e.getIntKey(), e.getCharValue()));
            return o;
        });
    }

    public static <T extends Int2ShortMap> Consumer<Builder<T>> int2ShortMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Int2ShortMaps.fastForEach(n, e -> o.put(e.getIntKey(), e.getShortValue()));
            return o;
        });
    }

    public static <T extends Int2ByteMap> Consumer<Builder<T>> int2ByteMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Int2ByteMaps.fastForEach(n, e -> o.put(e.getIntKey(), e.getByteValue()));
            return o;
        });
    }

    // === Long-based maps and sets ===

    public static <T extends LongSet> Consumer<Builder<T>> longSetBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            o.addAll(n);
            return o;
        });
    }

    public static <T extends Long2IntMap> Consumer<Builder<T>> long2IntMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Long2IntMaps.fastForEach(n, e -> o.put(e.getLongKey(), e.getIntValue()));
            return o;
        });
    }

    public static <T extends Long2LongMap> Consumer<Builder<T>> long2LongMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Long2LongMaps.fastForEach(n, e -> o.put(e.getLongKey(), e.getLongValue()));
            return o;
        });
    }

    public static <T extends Long2ObjectMap<V>, V> Consumer<Builder<T>> long2ObjectMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Long2ObjectMaps.fastForEach(n, e -> o.put(e.getLongKey(), e.getValue()));
            return o;
        });
    }

    public static <T extends Long2FloatMap> Consumer<Builder<T>> long2FloatMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Long2FloatMaps.fastForEach(n, e -> o.put(e.getLongKey(), e.getFloatValue()));
            return o;
        });
    }

    public static <T extends Long2DoubleMap> Consumer<Builder<T>> long2DoubleMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Long2DoubleMaps.fastForEach(n, e -> o.put(e.getLongKey(), e.getDoubleValue()));
            return o;
        });
    }

    public static <T extends Long2BooleanMap> Consumer<Builder<T>> long2BooleanMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Long2BooleanMaps.fastForEach(n, e -> o.put(e.getLongKey(), e.getBooleanValue()));
            return o;
        });
    }

    public static <T extends Long2CharMap> Consumer<Builder<T>> long2CharMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Long2CharMaps.fastForEach(n, e -> o.put(e.getLongKey(), e.getCharValue()));
            return o;
        });
    }

    public static <T extends Long2ShortMap> Consumer<Builder<T>> long2ShortMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Long2ShortMaps.fastForEach(n, e -> o.put(e.getLongKey(), e.getShortValue()));
            return o;
        });
    }

    public static <T extends Long2ByteMap> Consumer<Builder<T>> long2ByteMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Long2ByteMaps.fastForEach(n, e -> o.put(e.getLongKey(), e.getByteValue()));
            return o;
        });
    }

    // === Double-based maps and sets ===

    public static <T extends DoubleSet> Consumer<Builder<T>> doubleSetBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            o.addAll(n);
            return o;
        });
    }

    public static <T extends Double2IntMap> Consumer<Builder<T>> double2IntMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Double2IntMaps.fastForEach(n, e -> o.put(e.getDoubleKey(), e.getIntValue()));
            return o;
        });
    }

    public static <T extends Double2LongMap> Consumer<Builder<T>> double2LongMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Double2LongMaps.fastForEach(n, e -> o.put(e.getDoubleKey(), e.getLongValue()));
            return o;
        });
    }

    public static <T extends Double2ObjectMap<V>, V> Consumer<Builder<T>> double2ObjectMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Double2ObjectMaps.fastForEach(n, e -> o.put(e.getDoubleKey(), e.getValue()));
            return o;
        });
    }

    public static <T extends Double2FloatMap> Consumer<Builder<T>> double2FloatMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Double2FloatMaps.fastForEach(n, e -> o.put(e.getDoubleKey(), e.getFloatValue()));
            return o;
        });
    }

    public static <T extends Double2DoubleMap> Consumer<Builder<T>> double2DoubleMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Double2DoubleMaps.fastForEach(n, e -> o.put(e.getDoubleKey(), e.getDoubleValue()));
            return o;
        });
    }

    public static <T extends Double2BooleanMap> Consumer<Builder<T>> double2BooleanMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Double2BooleanMaps.fastForEach(n, e -> o.put(e.getDoubleKey(), e.getBooleanValue()));
            return o;
        });
    }

    public static <T extends Double2CharMap> Consumer<Builder<T>> double2CharMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Double2CharMaps.fastForEach(n, e -> o.put(e.getDoubleKey(), e.getCharValue()));
            return o;
        });
    }

    public static <T extends Double2ShortMap> Consumer<Builder<T>> double2ShortMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Double2ShortMaps.fastForEach(n, e -> o.put(e.getDoubleKey(), e.getShortValue()));
            return o;
        });
    }

    public static <T extends Double2ByteMap> Consumer<Builder<T>> double2ByteMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Double2ByteMaps.fastForEach(n, e -> o.put(e.getDoubleKey(), e.getByteValue()));
            return o;
        });
    }

    // === Float-based maps and sets ===

    public static <T extends FloatSet> Consumer<Builder<T>> floatSetBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            o.addAll(n);
            return o;
        });
    }

    public static <T extends Float2IntMap> Consumer<Builder<T>> float2IntMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Float2IntMaps.fastForEach(n, e -> o.put(e.getFloatKey(), e.getIntValue()));
            return o;
        });
    }

    public static <T extends Float2LongMap> Consumer<Builder<T>> float2LongMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Float2LongMaps.fastForEach(n, e -> o.put(e.getFloatKey(), e.getLongValue()));
            return o;
        });
    }

    public static <T extends Float2ObjectMap<V>, V> Consumer<Builder<T>> float2ObjectMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Float2ObjectMaps.fastForEach(n, e -> o.put(e.getFloatKey(), e.getValue()));
            return o;
        });
    }

    public static <T extends Float2FloatMap> Consumer<Builder<T>> float2FloatMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Float2FloatMaps.fastForEach(n, e -> o.put(e.getFloatKey(), e.getFloatValue()));
            return o;
        });
    }

    public static <T extends Float2DoubleMap> Consumer<Builder<T>> float2DoubleMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Float2DoubleMaps.fastForEach(n, e -> o.put(e.getFloatKey(), e.getDoubleValue()));
            return o;
        });
    }

    public static <T extends Float2BooleanMap> Consumer<Builder<T>> float2BooleanMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Float2BooleanMaps.fastForEach(n, e -> o.put(e.getFloatKey(), e.getBooleanValue()));
            return o;
        });
    }

    public static <T extends Float2CharMap> Consumer<Builder<T>> float2CharMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Float2CharMaps.fastForEach(n, e -> o.put(e.getFloatKey(), e.getCharValue()));
            return o;
        });
    }

    public static <T extends Float2ShortMap> Consumer<Builder<T>> float2ShortMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Float2ShortMaps.fastForEach(n, e -> o.put(e.getFloatKey(), e.getShortValue()));
            return o;
        });
    }

    public static <T extends Float2ByteMap> Consumer<Builder<T>> float2ByteMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Float2ByteMaps.fastForEach(n, e -> o.put(e.getFloatKey(), e.getByteValue()));
            return o;
        });
    }

    public static <T extends Object2IntMap<K>, K> Consumer<Builder<T>> object2IntMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Object2IntMaps.fastForEach(n, e -> o.put(e.getKey(), e.getIntValue()));
            return o;
        });
    }

    public static <T extends Object2LongMap<K>, K> Consumer<Builder<T>> object2LongMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Object2LongMaps.fastForEach(n, e -> o.put(e.getKey(), e.getLongValue()));
            return o;
        });
    }

    public static <T extends Object2ObjectMap<K, V>, K, V> Consumer<Builder<T>> object2ObjectMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Object2ObjectMaps.fastForEach(n, e -> o.put(e.getKey(), e.getValue()));
            return o;
        });
    }

    public static <T extends Object2FloatMap<K>, K> Consumer<Builder<T>> object2FloatMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Object2FloatMaps.fastForEach(n, e -> o.put(e.getKey(), e.getFloatValue()));
            return o;
        });
    }

    public static <T extends Object2DoubleMap<K>, K> Consumer<Builder<T>> object2DoubleMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Object2DoubleMaps.fastForEach(n, e -> o.put(e.getKey(), e.getDoubleValue()));
            return o;
        });
    }

    public static <T extends Object2BooleanMap<K>, K> Consumer<Builder<T>> object2BooleanMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Object2BooleanMaps.fastForEach(n, e -> o.put(e.getKey(), e.getBooleanValue()));
            return o;
        });
    }

    public static <T extends Object2CharMap<K>, K> Consumer<Builder<T>> object2CharMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Object2CharMaps.fastForEach(n, e -> o.put(e.getKey(), e.getCharValue()));
            return o;
        });
    }

    public static <T extends Object2ShortMap<K>, K> Consumer<Builder<T>> object2ShortMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Object2ShortMaps.fastForEach(n, e -> o.put(e.getKey(), e.getShortValue()));
            return o;
        });
    }

    public static <T extends Object2ByteMap<K>, K> Consumer<Builder<T>> object2ByteMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Object2ByteMaps.fastForEach(n, e -> o.put(e.getKey(), e.getByteValue()));
            return o;
        });
    }

    public static <T extends Reference2IntMap<K>, K> Consumer<Builder<T>> reference2IntMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Reference2IntMaps.fastForEach(n, e -> o.put(e.getKey(), e.getIntValue()));
            return o;
        });
    }

    public static <T extends Reference2LongMap<K>, K> Consumer<Builder<T>> reference2LongMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Reference2LongMaps.fastForEach(n, e -> o.put(e.getKey(), e.getLongValue()));
            return o;
        });
    }

    public static <T extends Reference2ObjectMap<K, V>, K, V> Consumer<Builder<T>> reference2ObjectMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Reference2ObjectMaps.fastForEach(n, e -> o.put(e.getKey(), e.getValue()));
            return o;
        });
    }

    public static <T extends Reference2FloatMap<K>, K> Consumer<Builder<T>> reference2FloatMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Reference2FloatMaps.fastForEach(n, e -> o.put(e.getKey(), e.getFloatValue()));
            return o;
        });
    }

    public static <T extends Reference2DoubleMap<K>, K> Consumer<Builder<T>> reference2DoubleMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Reference2DoubleMaps.fastForEach(n, e -> o.put(e.getKey(), e.getDoubleValue()));
            return o;
        });
    }

    public static <T extends Reference2BooleanMap<K>, K> Consumer<Builder<T>> reference2BooleanMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Reference2BooleanMaps.fastForEach(n, e -> o.put(e.getKey(), e.getBooleanValue()));
            return o;
        });
    }

    public static <T extends Reference2CharMap<K>, K> Consumer<Builder<T>> reference2CharMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Reference2CharMaps.fastForEach(n, e -> o.put(e.getKey(), e.getCharValue()));
            return o;
        });
    }

    public static <T extends Reference2ShortMap<K>, K> Consumer<Builder<T>> reference2ShortMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Reference2ShortMaps.fastForEach(n, e -> o.put(e.getKey(), e.getShortValue()));
            return o;
        });
    }

    public static <T extends Reference2ByteMap<K>, K> Consumer<Builder<T>> reference2ByteMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Reference2ByteMaps.fastForEach(n, e -> o.put(e.getKey(), e.getByteValue()));
            return o;
        });
    }

    // === Char-based maps and sets ===

    public static <T extends CharSet> Consumer<Builder<T>> charSetBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            o.addAll(n);
            return o;
        });
    }

    public static <T extends Char2IntMap> Consumer<Builder<T>> char2IntMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Char2IntMaps.fastForEach(n, e -> o.put(e.getCharKey(), e.getIntValue()));
            return o;
        });
    }

    public static <T extends Char2LongMap> Consumer<Builder<T>> char2LongMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Char2LongMaps.fastForEach(n, e -> o.put(e.getCharKey(), e.getLongValue()));
            return o;
        });
    }

    public static <T extends Char2ObjectMap<V>, V> Consumer<Builder<T>> char2ObjectMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Char2ObjectMaps.fastForEach(n, e -> o.put(e.getCharKey(), e.getValue()));
            return o;
        });
    }

    public static <T extends Char2FloatMap> Consumer<Builder<T>> char2FloatMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Char2FloatMaps.fastForEach(n, e -> o.put(e.getCharKey(), e.getFloatValue()));
            return o;
        });
    }

    public static <T extends Char2DoubleMap> Consumer<Builder<T>> char2DoubleMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Char2DoubleMaps.fastForEach(n, e -> o.put(e.getCharKey(), e.getDoubleValue()));
            return o;
        });
    }

    public static <T extends Char2BooleanMap> Consumer<Builder<T>> char2BooleanMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Char2BooleanMaps.fastForEach(n, e -> o.put(e.getCharKey(), e.getBooleanValue()));
            return o;
        });
    }

    public static <T extends Char2CharMap> Consumer<Builder<T>> char2CharMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Char2CharMaps.fastForEach(n, e -> o.put(e.getCharKey(), e.getCharValue()));
            return o;
        });
    }

    public static <T extends Char2ShortMap> Consumer<Builder<T>> char2ShortMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Char2ShortMaps.fastForEach(n, e -> o.put(e.getCharKey(), e.getShortValue()));
            return o;
        });
    }

    public static <T extends Char2ByteMap> Consumer<Builder<T>> char2ByteMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Char2ByteMaps.fastForEach(n, e -> o.put(e.getCharKey(), e.getByteValue()));
            return o;
        });
    }

    // === Short-based maps and sets ===

    public static <T extends ShortSet> Consumer<Builder<T>> shortSetBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            o.addAll(n);
            return o;
        });
    }

    public static <T extends Short2IntMap> Consumer<Builder<T>> short2IntMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Short2IntMaps.fastForEach(n, e -> o.put(e.getShortKey(), e.getIntValue()));
            return o;
        });
    }

    public static <T extends Short2LongMap> Consumer<Builder<T>> short2LongMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Short2LongMaps.fastForEach(n, e -> o.put(e.getShortKey(), e.getLongValue()));
            return o;
        });
    }

    public static <T extends Short2ObjectMap<V>, V> Consumer<Builder<T>> short2ObjectMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Short2ObjectMaps.fastForEach(n, e -> o.put(e.getShortKey(), e.getValue()));
            return o;
        });
    }

    public static <T extends Short2FloatMap> Consumer<Builder<T>> short2FloatMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Short2FloatMaps.fastForEach(n, e -> o.put(e.getShortKey(), e.getFloatValue()));
            return o;
        });
    }

    public static <T extends Short2DoubleMap> Consumer<Builder<T>> short2DoubleMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Short2DoubleMaps.fastForEach(n, e -> o.put(e.getShortKey(), e.getDoubleValue()));
            return o;
        });
    }

    public static <T extends Short2BooleanMap> Consumer<Builder<T>> short2BooleanMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Short2BooleanMaps.fastForEach(n, e -> o.put(e.getShortKey(), e.getBooleanValue()));
            return o;
        });
    }

    public static <T extends Short2CharMap> Consumer<Builder<T>> short2CharMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Short2CharMaps.fastForEach(n, e -> o.put(e.getShortKey(), e.getCharValue()));
            return o;
        });
    }

    public static <T extends Short2ShortMap> Consumer<Builder<T>> short2ShortMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Short2ShortMaps.fastForEach(n, e -> o.put(e.getShortKey(), e.getShortValue()));
            return o;
        });
    }

    public static <T extends Short2ByteMap> Consumer<Builder<T>> short2ByteMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Short2ByteMaps.fastForEach(n, e -> o.put(e.getShortKey(), e.getByteValue()));
            return o;
        });
    }

    // === Byte-based maps and sets ===

    public static <T extends ByteSet> Consumer<Builder<T>> byteSetBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            o.addAll(n);
            return o;
        });
    }

    public static <T extends Byte2IntMap> Consumer<Builder<T>> byte2IntMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Byte2IntMaps.fastForEach(n, e -> o.put(e.getByteKey(), e.getIntValue()));
            return o;
        });
    }

    public static <T extends Byte2LongMap> Consumer<Builder<T>> byte2LongMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Byte2LongMaps.fastForEach(n, e -> o.put(e.getByteKey(), e.getLongValue()));
            return o;
        });
    }

    public static <T extends Byte2ObjectMap<V>, V> Consumer<Builder<T>> byte2ObjectMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Byte2ObjectMaps.fastForEach(n, e -> o.put(e.getByteKey(), e.getValue()));
            return o;
        });
    }

    public static <T extends Byte2FloatMap> Consumer<Builder<T>> byte2FloatMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Byte2FloatMaps.fastForEach(n, e -> o.put(e.getByteKey(), e.getFloatValue()));
            return o;
        });
    }

    public static <T extends Byte2DoubleMap> Consumer<Builder<T>> byte2DoubleMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Byte2DoubleMaps.fastForEach(n, e -> o.put(e.getByteKey(), e.getDoubleValue()));
            return o;
        });
    }

    public static <T extends Byte2BooleanMap> Consumer<Builder<T>> byte2BooleanMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Byte2BooleanMaps.fastForEach(n, e -> o.put(e.getByteKey(), e.getBooleanValue()));
            return o;
        });
    }

    public static <T extends Byte2CharMap> Consumer<Builder<T>> byte2CharMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Byte2CharMaps.fastForEach(n, e -> o.put(e.getByteKey(), e.getCharValue()));
            return o;
        });
    }

    public static <T extends Byte2ShortMap> Consumer<Builder<T>> byte2ShortMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Byte2ShortMaps.fastForEach(n, e -> o.put(e.getByteKey(), e.getShortValue()));
            return o;
        });
    }

    public static <T extends Byte2ByteMap> Consumer<Builder<T>> byte2ByteMapBuilder(Function<T, T> createFunction) {
        return b -> b.createFunction(createFunction).mergeFunction((o, n) -> {
            Byte2ByteMaps.fastForEach(n, e -> o.put(e.getByteKey(), e.getByteValue()));
            return o;
        });
    }

    @Override
    public String toString() {
        return name;
    }

    public final static class Builder<T> {

        private final String name;
        private DataSyncCodec<T> codec;
        private BiFunction<T, T, T> mergeFunction;
        private Function<T, T> createFunction;

        public Builder(String name) {
            this.name = name;
        }

        public Builder<T> codec(DataSyncCodec<T> codec) {
            this.codec = codec;
            return this;
        }

        public Builder<T> mergeFunction(BiFunction<T, T, T> mergeFunction) {
            this.mergeFunction = mergeFunction;
            return this;
        }

        public Builder<T> createFunction(Function<T, T> createFunction) {
            this.createFunction = createFunction;
            return this;
        }

        public DataComponentKey<T> build() {
            if (mergeFunction != null && createFunction != null) {
                return new DataComponentKey<>(name, codec, mergeFunction, createFunction);
            } else if (mergeFunction != null) {
                return new DataComponentKey<>(name, codec, mergeFunction, n -> n);
            } else if (createFunction != null) {
                return new DataComponentKey<>(name, codec, (o, n) -> n, createFunction);
            } else {
                return new DataComponentKey<>(name, codec);
            }
        }
    }
}
