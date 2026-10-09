package com.gto.datasynclib.datastream.codec;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.RecordBuilder.AbstractStringBuilder;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Mojang's {@link DynamicOps} over the {@link JavaValueOps} carrier — the native twin of the Data
 * replacement for the retired {@code DataOps}, and the bridge that lets a Mojang
 * {@link com.mojang.serialization.Codec}
 * run on plain Java objects.
 *
 * <p>It is what {@link ByteBufCodecs#fromCodec(com.mojang.serialization.Codec)} and
 * retired {@code DataCodec.of(Codec)} needed: a representation to hand the codec. Numbers, strings,
 * lists and
 * maps map onto {@code Integer}, {@code String}, {@link ArrayList} and {@link HashMap}, i.e.
 * onto the carriers {@link JavaValueOps} already knows, so a value encoded here can be written to
 * storage with {@link ValueOps#toBytes(Object)} without a conversion step.</p>
 *
 * <h3>The shape of a boolean</h3>
 * <p>{@link #createBoolean(boolean)} builds the byte {@code 0}/{@code 1} — the shape the carrier and
 * the old Data encoding both use — while {@link #getBooleanValue(Object)} accepts a {@link Boolean}
 * or such a byte, so a boolean written by either route reads back.</p>
 */
public final class JavaOps implements DynamicOps<Object> {

    public static final JavaOps INSTANCE = new JavaOps();

    private JavaOps() {
    }

    @Override
    public Object empty() {
        return ValueOps.Null.INSTANCE;
    }

    @Override
    public Object emptyList() {
        return Collections.emptyList();
    }

    @Override
    public Object emptyMap() {
        return Collections.emptyMap();
    }

    @Override
    public <U> U convertTo(DynamicOps<U> outOps, Object input) {
        // One pattern switch over the value itself. Naming the type first (getTypeId) and then reading
        // the value back through the ops classifies every value twice; the pattern has already proved
        // the type each branch needs, so the branch is just the call.
        return switch (input) {
            case null -> outOps.empty();
            case ValueOps.Null ignored -> outOps.empty();
            case Integer value -> outOps.createInt(value);
            case Long value -> outOps.createLong(value);
            case String value -> outOps.createString(value);
            case Boolean value -> outOps.createBoolean(value);
            case List<?> ignored -> convertList(outOps, input);
            case Map<?, ?> ignored -> convertMap(outOps, input);
            case Byte value -> outOps.createByte(value);
            case Short value -> outOps.createShort(value);
            case Float value -> outOps.createFloat(value);
            case Double value -> outOps.createDouble(value);
            case byte[] value -> outOps.createByteList(ByteBuffer.wrap(value));
            case int[] value -> outOps.createIntList(Arrays.stream(value));
            case long[] value -> outOps.createLongList(Arrays.stream(value));
            // no NBT/Json equivalent, and DFU has no createChar either: a Character, a custom payload and
            // a SELF object all land here
            default -> throw new MatchException("Unsupported value type for conversion: " + describe(input), null);
        };
    }

    private static String describe(Object input) {
        return input == null ? "null" : input.getClass().getName();
    }

    @Override
    public DataResult<Number> getNumberValue(Object input) {
        return input instanceof Number number ? DataResult.success(number) : DataResult.error(() -> "Not a number");
    }

    /**
     * The carrier value for a DFU number — the number itself, since a boxed primitive already is one
     * here. Kept for parity with the Data-side ops, which had to pick a subtype.
     */
    public Object createNumeric(Number n) {
        return n;
    }

    @Override
    public DataResult<Boolean> getBooleanValue(Object input) {
        if (input instanceof Boolean value) return DataResult.success(value);
        if (input instanceof Byte value) return DataResult.success(value == 1);
        return DataResult.error(() -> "Not a boolean");
    }

    @Override
    public Object createBoolean(boolean value) {
        return value ? (byte) 1 : (byte) 0;
    }

    @Override
    public Object createByte(byte value) {
        return value;
    }

    @Override
    public Object createShort(short value) {
        return value;
    }

    @Override
    public Object createInt(int value) {
        return value;
    }

    @Override
    public Object createLong(long value) {
        return value;
    }

    @Override
    public Object createFloat(float value) {
        return value;
    }

    @Override
    public Object createDouble(double value) {
        return value;
    }

    @Override
    public DataResult<String> getStringValue(Object input) {
        return input instanceof String value ? DataResult.success(value) : DataResult.error(() -> "Not a string");
    }

    @Override
    public Object createString(String value) {
        return value;
    }

    /**
     * {@return whether this is the ops' own "no value" — DFU hands {@link #empty()} around as a prefix}
     */
    private static boolean absent(Object value) {
        return value == null || value == ValueOps.Null.INSTANCE;
    }

    @Override
    public DataResult<Object> mergeToList(Object list, Object value) {
        // not List.of(value): a null value is a value here (NULL is a real type), and List.of refuses it
        if (absent(list)) {
            var merged = new ArrayList<>(1);
            merged.add(value);
            return DataResult.success(merged);
        }
        if (list instanceof List<?> values) {
            var merged = new ArrayList<Object>(values);
            merged.add(value);
            return DataResult.success(merged);
        }
        return DataResult.error(() -> "mergeToList called with not a list: " + list, list);
    }

    @Override
    public DataResult<Object> mergeToList(Object list, List<Object> values) {
        if (absent(list)) return DataResult.success(new ArrayList<>(values));
        if (list instanceof List<?> existing) {
            var merged = new ArrayList<Object>(existing);
            merged.addAll(values);
            return DataResult.success(merged);
        }
        return DataResult.error(() -> "mergeToList called with not a list: " + list, list);
    }

    @Override
    public DataResult<Object> mergeToMap(Object map, Object key, Object value) {
        if (!(absent(map) || map instanceof Map)) {
            return DataResult.error(() -> "mergeToMap called with not a map: " + map, map);
        }
        if (!(key instanceof String name)) {
            return DataResult.error(() -> "key is not a string: " + key, map);
        }
        var merged = copyStringMap(map);
        merged.put(name, value);
        return DataResult.success(merged);
    }

    @Override
    public DataResult<Object> mergeToMap(Object map, MapLike<Object> values) {
        if (!(absent(map) || map instanceof Map)) {
            return DataResult.error(() -> "mergeToMap called with not a map: " + map, map);
        }
        var merged = copyStringMap(map);
        var missed = new ArrayList<>();
        Iterator<Pair<Object, Object>> entries = values.entries().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getFirst() instanceof String name) {
                merged.put(name, entry.getSecond());
            } else {
                missed.add(entry.getFirst());
            }
        }
        return missed.isEmpty()
                ? DataResult.success(merged)
                : DataResult.error(() -> "some keys are not strings: " + missed, merged);
    }

    @Override
    public DataResult<Object> mergeToMap(Object map, Map<Object, Object> values) {
        if (!(absent(map) || map instanceof Map)) {
            return DataResult.error(() -> "mergeToMap called with not a map: " + map, map);
        }
        var merged = copyStringMap(map);
        var missed = new ArrayList<>();
        values.forEach((key, value) -> {
            if (key instanceof String name) {
                merged.put(name, value);
            } else {
                missed.add(key);
            }
        });
        return missed.isEmpty()
                ? DataResult.success(merged)
                : DataResult.error(() -> "some keys are not strings: " + missed, merged);
    }

    @Override
    public DataResult<Stream<Pair<Object, Object>>> getMapValues(Object input) {
        return input instanceof Map<?, ?> map
                ? DataResult.success(map.entrySet().stream().map(entry -> Pair.of(entry.getKey(), entry.getValue())))
                : DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public DataResult<Consumer<BiConsumer<Object, Object>>> getMapEntries(Object input) {
        return input instanceof Map<?, ?> map
                ? DataResult.success(map::forEach)
                : DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public DataResult<MapLike<Object>> getMap(Object input) {
        if (!(input instanceof Map<?, ?> map)) return DataResult.error(() -> "Not a map: " + input);
        return DataResult.success(new MapLike<>() {

            @Override
            public Object get(Object key) {
                if (key instanceof String name) return map.get(name);
                throw new UnsupportedOperationException("Cannot get a value with a non-string key: " + key);
            }

            @Override
            public Object get(String key) {
                return map.get(key);
            }

            @Override
            public Stream<Pair<Object, Object>> entries() {
                return map.entrySet().stream().map(entry -> Pair.of(entry.getKey(), entry.getValue()));
            }

            @Override
            public String toString() {
                return "MapLike[" + map + "]";
            }
        });
    }

    @Override
    public Object createMap(Stream<Pair<Object, Object>> map) {
        var result = new HashMap<String, Object>();
        map.forEach(entry -> {
            if (entry.getFirst() instanceof String name) {
                result.put(name, entry.getSecond());
            } else {
                throw new UnsupportedOperationException("Cannot create a map with a non-string key: " + entry.getFirst());
            }
        });
        return result;
    }

    @Override
    public DataResult<Stream<Object>> getStream(Object input) {
        return input instanceof List<?> list
                ? DataResult.success(list.stream().map(element -> (Object) element))
                : DataResult.error(() -> "Not a list: " + input);
    }

    @Override
    public DataResult<Consumer<Consumer<Object>>> getList(Object input) {
        return input instanceof List<?> list
                ? DataResult.success(list::forEach)
                : DataResult.error(() -> "Not a list: " + input);
    }

    @Override
    public DataResult<ByteBuffer> getByteBuffer(Object input) {
        return input instanceof byte[] bytes
                ? DataResult.success(ByteBuffer.wrap(bytes))
                : DynamicOps.super.getByteBuffer(input);
    }

    @Override
    public Object createByteList(ByteBuffer input) {
        var whole = input.duplicate().clear();
        var bytes = new byte[input.capacity()];
        whole.get(0, bytes, 0, bytes.length);
        return bytes;
    }

    @Override
    public DataResult<IntStream> getIntStream(Object input) {
        return input instanceof int[] ints
                ? DataResult.success(Arrays.stream(ints))
                : DynamicOps.super.getIntStream(input);
    }

    @Override
    public Object createIntList(IntStream input) {
        return input.toArray();
    }

    @Override
    public DataResult<LongStream> getLongStream(Object input) {
        return input instanceof long[] longs
                ? DataResult.success(Arrays.stream(longs))
                : DynamicOps.super.getLongStream(input);
    }

    @Override
    public Object createLongList(LongStream input) {
        return input.toArray();
    }

    @Override
    public Object createList(Stream<Object> input) {
        return input.collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public Object remove(Object input, String key) {
        if (!(input instanceof Map<?, ?>)) return input;
        var result = copyStringMap(input);
        result.remove(key);
        return result;
    }

    @Override
    public String toString() {
        return "Java";
    }

    @Override
    public RecordBuilder<Object> mapBuilder() {
        return new JavaRecordBuilder();
    }

    /**
     * The carrier's own string map: the copy is a fresh {@link HashMap} so a merge never
     * mutates the input, and a non-string key is rejected the way the carrier would.
     */
    private static HashMap<String, Object> copyStringMap(Object map) {
        var result = new HashMap<String, Object>();
        if (map instanceof Map<?, ?> source) {
            source.forEach((key, value) -> {
                if (!(key instanceof String name)) {
                    throw new UnsupportedOperationException("Cannot use a non-string key: " + key);
                }
                result.put(name, value);
            });
        }
        return result;
    }

    private final class JavaRecordBuilder extends AbstractStringBuilder<Object, Map<String, Object>> {

        private JavaRecordBuilder() {
            super(JavaOps.this);
        }

        @Override
        protected Map<String, Object> initBuilder() {
            return new HashMap<>();
        }

        @Override
        protected Map<String, Object> append(String key, Object value, Map<String, Object> builder) {
            builder.put(key, value);
            return builder;
        }

        @Override
        protected DataResult<Object> build(Map<String, Object> builder, Object prefix) {
            if (prefix == null || JavaValueOps.INSTANCE.isNull(prefix)) {
                return DataResult.success(builder);
            }
            if (!(prefix instanceof Map)) {
                return DataResult.error(() -> "mergeToMap called with not a map: " + prefix, prefix);
            }
            var result = copyStringMap(prefix);
            result.putAll(builder);
            return DataResult.success(result);
        }
    }
}
