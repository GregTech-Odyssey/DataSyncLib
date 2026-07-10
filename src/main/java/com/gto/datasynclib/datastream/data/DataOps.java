package com.gto.datasynclib.datastream.data;

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
 * Implementation of Mojang's {@link com.mojang.serialization.DynamicOps}{@code <Data>} interface,
 * providing the bridge between the DataSyncLib {@link Data} type system and Mojang's DataFixerUpper
 * serialization framework.
 *
 * <p>Enables {@link Data} objects to be used with Mojang {@link com.mojang.serialization.Codec}s
 * for structured serialization. Converts Data values to/from standard DFU primitives (numbers,
 * strings, lists, maps), implements map/list merge operations, and provides a
 * {@link RecordBuilder} for codec-driven record construction backed by {@link StringMapData}.
 *
 * <p>The singleton {@link #INSTANCE} is the canonical instance for all DataOps operations.
 *
 * @see Data#CODEC
 * @see com.mojang.serialization.DynamicOps
 */
public final class DataOps implements DynamicOps<Data> {

    public static final DataOps INSTANCE = new DataOps();

    private DataOps() {
    }

    public Data empty() {
        return NullData.INSTANCE;
    }

    public Data emptyList() {
        return ListData.EMPTY;
    }

    public Data emptyMap() {
        return StringMapData.EMPTY;
    }

    public <U> U convertTo(DynamicOps<U> outOps, Data input) {
        return switch (input.getId()) {
            case Data.NULL -> outOps.empty();
            case Data.BOOLEAN -> outOps.createBoolean(input.getBoolean());
            case Data.BYTE -> outOps.createByte(input.getByte());
            case Data.SHORT -> outOps.createShort(input.getShort());
            case Data.INT -> outOps.createInt(input.getInt());
            case Data.LONG -> outOps.createLong(input.getLong());
            case Data.FLOAT -> outOps.createFloat(input.getFloat());
            case Data.DOUBLE -> outOps.createDouble(input.getDouble());
            case Data.STRING -> outOps.createString(input.getString());
            case Data.LIST -> this.convertList(outOps, input);
            case Data.STRING_MAP -> this.convertMap(outOps, input);
            case Data.BYTE_ARRAY -> outOps.createByteList(ByteBuffer.wrap(input.getByteArray()));
            case Data.INT_ARRAY -> outOps.createIntList(Arrays.stream(input.getIntArray()));
            case Data.LONG_ARRAY -> outOps.createLongList(Arrays.stream(input.getLongArray()));
            default ->
                    throw new MatchException("Unsupported Data type for conversion: " + input.getId() + " (" + input.getClass().getSimpleName() + ")", null);
        };
    }

    public DataResult<Number> getNumberValue(Data input) {
        return input instanceof NumericData data ? DataResult.success(data.box()) : DataResult.error(() -> "Not a number");
    }

    public NumericData createNumeric(Number n) {
        return switch (n) {
            case Byte b -> ByteData.valueOf(b);
            case Short s -> ShortData.valueOf(s);
            case Integer i -> IntData.valueOf(i);
            case Long l -> LongData.valueOf(l);
            case Float f -> FloatData.valueOf(f);
            case Double d -> DoubleData.valueOf(d);
            default -> throw new MatchException("Unsupported Number type: " + n.getClass().getName(), null);
        };
    }

    public DataResult<Boolean> getBooleanValue(final Data input) {
        return input instanceof ByteData(
                byte value
        ) ? DataResult.success(value == 1) : DataResult.error(() -> "Not a boolean");
    }

    public ByteData createBoolean(final boolean value) {
        return ByteData.valueOf(value);
    }

    public ByteData createByte(byte value) {
        return ByteData.valueOf(value);
    }

    public ShortData createShort(short value) {
        return ShortData.valueOf(value);
    }

    public IntData createInt(int value) {
        return IntData.valueOf(value);
    }

    public LongData createLong(long value) {
        return LongData.valueOf(value);
    }

    public FloatData createFloat(float value) {
        return FloatData.valueOf(value);
    }

    public DoubleData createDouble(double value) {
        return DoubleData.valueOf(value);
    }

    public DataResult<String> getStringValue(Data input) {
        return input instanceof StringData(
                String var4
        ) ? DataResult.success(var4) : DataResult.error(() -> "Not a string");
    }

    public StringData createString(String value) {
        return StringData.valueOf(value);
    }

    public DataResult<Data> mergeToList(Data list, Data value) {
        if (list.isNull()) return DataResult.success(ListData.of(value));
        if (list instanceof ListData listData) {
            listData = listData.shallowCopy();
            listData.add(value);
            return DataResult.success(listData);
        }
        return DataResult.error(() -> "mergeToList called with not a value: " + list, list);
    }

    public DataResult<Data> mergeToList(Data list, List<Data> values) {
        if (list.isNull()) return DataResult.success(ListData.of(values));
        if (list instanceof ListData listData) {
            listData = listData.shallowCopy();
            listData.addAll(values);
            return DataResult.success(listData);
        }
        return DataResult.error(() -> "mergeToList called with not a value: " + list, list);
    }

    public DataResult<Data> mergeToMap(Data map, Data key, Data value) {
        if (!(map instanceof StringMapData) && !map.isNull()) {
            return DataResult.error(() -> "mergeToMap called with not a value: " + map, map);
        } else if (key instanceof StringData(String Data)) {
            StringMapData output = map instanceof StringMapData Datax ? Datax.shallowCopy() : new StringMapData();
            output.put(Data, value);
            return DataResult.success(output);
        } else {
            return DataResult.error(() -> "key is not a string: " + key, map);
        }
    }

    public DataResult<Data> mergeToMap(Data map, MapLike<Data> values) {
        if (!(map instanceof StringMapData) && !map.isNull()) {
            return DataResult.error(() -> "mergeToMap called with not a value: " + map, map);
        } else {
            Iterator<Pair<Data, Data>> valuesIterator = values.entries().iterator();
            if (!valuesIterator.hasNext()) {
                return map.isNull() ? DataResult.success(StringMapData.EMPTY) : DataResult.success(map);
            } else {
                StringMapData output = map instanceof StringMapData Data ? Data.shallowCopy() : new StringMapData();
                List<Data> missed = new ArrayList<>();
                valuesIterator.forEachRemaining(entry -> {
                    Data key = entry.getFirst();
                    if (key instanceof StringData(String patt1$temp)) {
                        output.put(patt1$temp, entry.getSecond());
                    } else {
                        missed.add(key);
                    }
                });
                return !missed.isEmpty() ? DataResult.error(() -> "some keys are not strings: " + missed, output) : DataResult.success(output);
            }
        }
    }

    public DataResult<Data> mergeToMap(Data map, Map<Data, Data> values) {
        if (!(map instanceof StringMapData) && !map.isNull()) {
            return DataResult.error(() -> "mergeToMap called with not a value: " + map, map);
        } else if (values.isEmpty()) {
            return map.isNull() ? DataResult.success(StringMapData.EMPTY) : DataResult.success(map);
        } else {
            StringMapData output = map instanceof StringMapData Data ? Data.shallowCopy() : new StringMapData();
            List<Data> missed = new ArrayList<>();
            values.forEach((k, v) -> {
                if (k instanceof StringData(String var10)) {
                    output.put(var10, v);
                } else {
                    missed.add(k);
                }
            });
            return !missed.isEmpty() ? DataResult.error(() -> "some keys are not strings: " + missed, output) : DataResult.success(output);
        }
    }

    public DataResult<Stream<Pair<Data, Data>>> getMapValues(Data input) {
        return input instanceof StringMapData(
                Map<String, Data> value
        ) ? DataResult.success(value.entrySet().stream().map(entry -> Pair.of(StringData.valueOf(entry.getKey()), entry.getValue()))) : DataResult.error(() -> "Not a value: " + input);
    }

    public DataResult<Consumer<BiConsumer<Data, Data>>> getMapEntries(Data input) {
        return input instanceof StringMapData(
                Map<String, Data> value
        ) ? DataResult.success(c -> value.forEach((k, v) -> c.accept(StringData.valueOf(k), v))) : DataResult.error(() -> "Not a value: " + input);
    }

    public DataResult<MapLike<Data>> getMap(Data input) {
        return input instanceof StringMapData(Map<String, Data> value) ? DataResult.success(new MapLike<>() {

            public Data get(Data key) {
                if (key instanceof StringData(String var4)) {
                    return value.get(var4);
                } else {
                    throw new UnsupportedOperationException("Cannot get value entry with non-string key: " + key);
                }
            }

            public Data get(String key) {
                return value.get(key);
            }

            @Override
            public Stream<Pair<Data, Data>> entries() {
                return value.entrySet().stream().map(entry -> Pair.of(StringData.valueOf(entry.getKey()), entry.getValue()));
            }

            @Override
            public String toString() {
                return "MapLike[" + value + "]";
            }
        }) : DataResult.error(() -> "Not a value: " + input);
    }

    public Data createMap(Stream<Pair<Data, Data>> map) {
        StringMapData Data = new StringMapData();
        map.forEach(entry -> {
            Data key = entry.getFirst();
            Data value = entry.getSecond();
            if (key instanceof StringData(String patt1$temp)) {
                Data.put(patt1$temp, value);
            } else {
                throw new UnsupportedOperationException("Cannot create value with non-string key: " + key);
            }
        });
        return Data;
    }

    public DataResult<Stream<Data>> getStream(Data input) {
        return input instanceof ListData(
                List<Data> value
        ) ? DataResult.success(value.stream()) : DataResult.error(() -> "Not a value");
    }

    public DataResult<Consumer<Consumer<Data>>> getList(Data input) {
        return input instanceof ListData collection ? DataResult.success(collection::forEach) : DataResult.error(() -> "Not a value: " + input);
    }

    public DataResult<ByteBuffer> getByteBuffer(Data input) {
        return input instanceof ByteArrayData array ? DataResult.success(ByteBuffer.wrap(array.getByteArray())) : DynamicOps.super.getByteBuffer(input);
    }

    public Data createByteList(ByteBuffer input) {
        ByteBuffer wholeBuffer = input.duplicate().clear();
        byte[] bytes = new byte[input.capacity()];
        wholeBuffer.get(0, bytes, 0, bytes.length);
        return new ByteArrayData(bytes);
    }

    public DataResult<IntStream> getIntStream(Data input) {
        return input instanceof IntArrayData array ? DataResult.success(Arrays.stream(array.getIntArray())) : DynamicOps.super.getIntStream(input);
    }

    public Data createIntList(IntStream input) {
        return new IntArrayData(input.toArray());
    }

    public DataResult<LongStream> getLongStream(Data input) {
        return input instanceof LongArrayData array ? DataResult.success(Arrays.stream(array.getLongArray())) : DynamicOps.super.getLongStream(input);
    }

    public Data createLongList(LongStream input) {
        return new LongArrayData(input.toArray());
    }

    public Data createList(Stream<Data> input) {
        return new ListData(input.collect(Collectors.toList()));
    }

    public Data remove(Data input, String key) {
        if (input instanceof StringMapData Data) {
            StringMapData result = Data.shallowCopy();
            result.remove(key);
            return result;
        } else {
            return input;
        }
    }

    @Override
    public String toString() {
        return "Data";
    }

    @Override
    public RecordBuilder<Data> mapBuilder() {
        return new DataRecordBuilder();
    }

    private class DataRecordBuilder extends AbstractStringBuilder<Data, StringMapData> {

        protected DataRecordBuilder() {
            super(DataOps.this);
        }

        protected StringMapData initBuilder() {
            return new StringMapData();
        }

        protected StringMapData append(String key, Data value, StringMapData builder) {
            builder.put(key, value);
            return builder;
        }

        protected DataResult<Data> build(StringMapData builder, Data prefix) {
            if (prefix == null || prefix.isNull()) {
                return DataResult.success(builder);
            } else if (!(prefix instanceof StringMapData compound)) {
                return DataResult.error(() -> "mergeToMap called with not a value: " + prefix, prefix);
            } else {
                StringMapData result = compound.shallowCopy();
                builder.value().forEach(result::put);
                return DataResult.success(result);
            }
        }
    }
}
