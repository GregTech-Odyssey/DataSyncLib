package com.gto.datasynclib.datastream.data;

import com.gto.datasynclib.datastream.codec.DataDecoder;
import com.gto.datasynclib.datastream.codec.DataEncoder;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a long-to-Data map (FastUtil Long2ObjectMap) in the Data type system. Wire format ID: 18.
 */
public record LongMapData(Long2ObjectMap<Data> value) implements MapData {

    public static final LongMapData EMPTY = new LongMapData(Long2ObjectMaps.emptyMap());

    public LongMapData() {
        this(new Long2ObjectOpenHashMap<>());
    }

    public LongMapData(int initialCapacity) {
        this(new Long2ObjectOpenHashMap<>(initialCapacity));
    }

    public static LongMapData read(ByteBuf stream) {
        var size = Data.readVarInt(stream);
        var map = new Long2ObjectOpenHashMap<Data>(size);
        for (int i = 0; i < size; i++) {
            map.put(Data.readVarLong(stream), Data.readData(stream));
        }
        return new LongMapData(map);
    }

    public LongMapData shallowCopy() {
        return new LongMapData(new Long2ObjectOpenHashMap<>(this.value));
    }

    public void clear() {
        this.value.clear();
    }

    @Override
    public int size() {
        return this.value.size();
    }

    @Override
    public boolean isEmpty() {
        return this.value.isEmpty();
    }

    public boolean containsKey(long key) {
        return this.value.containsKey(key);
    }

    public Data remove(long key) {
        return this.value.remove(key);
    }

    public <T> Data put(long key, Encoder<? super T> codec, T data) {
        return this.value.put(key, codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(long key, DataEncoder<? super T> codec, T data) {
        return this.value.put(key, codec.encode(data));
    }

    public Data put(long key, Data data) {
        return this.value.put(key, data);
    }

    public void putBoolean(long key, boolean data) {
        this.value.put(key, ByteData.valueOf(data));
    }

    public void putByte(long key, byte data) {
        this.value.put(key, ByteData.valueOf(data));
    }

    public void putShort(long key, short data) {
        this.value.put(key, ShortData.valueOf(data));
    }

    public void putChar(long key, char data) {
        this.value.put(key, CharData.valueOf(data));
    }

    public void putInt(long key, int data) {
        this.value.put(key, IntData.valueOf(data));
    }

    public void putLong(long key, long data) {
        this.value.put(key, LongData.valueOf(data));
    }

    public void putFloat(long key, float data) {
        this.value.put(key, FloatData.valueOf(data));
    }

    public void putDouble(long key, double data) {
        this.value.put(key, DoubleData.valueOf(data));
    }

    public void putString(long key, String data) {
        this.value.put(key, StringData.valueOf(data));
    }

    public void putUUID(long key, UUID data) {
        this.value.put(key, Data.valueOf(data));
    }

    public void putBigInteger(long key, BigInteger data) {
        this.value.put(key, Data.valueOf(data));
    }

    public boolean getBoolean(long key) {
        var data = this.value.get(key);
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(long key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(long key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(long key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(long key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(long key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(long key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(long key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getDouble();
    }

    @Nullable
    public Data get(long key) {
        return this.value.get(key);
    }

    @NotNull
    public ListData getList(long key) {
        if (this.value.get(key) instanceof ListData listData) {
            return listData;
        }
        return ListData.EMPTY;
    }

    @NotNull
    public LongMapData getMap(long key) {
        if (this.value.get(key) instanceof LongMapData mapData) {
            return mapData;
        }
        return LongMapData.EMPTY;
    }

    @Nullable
    public String getString(long key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getString();
    }

    @Nullable
    public BigInteger getBigInteger(long key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getBigInteger();
    }

    @Nullable
    public UUID getUUID(long key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getUUID();
    }

    @Nullable
    public <T> T get(long key, Decoder<? extends T> codec) {
        var data = this.value.get(key);
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(long key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(key);
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @NotNull
    public Optional<Data> getOptional(long key) {
        return Optional.ofNullable(this.value.get(key));
    }

    @NotNull
    public Optional<String> getOptionalString(long key) {
        return Optional.ofNullable(getString(key));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(long key) {
        return Optional.ofNullable(getBigInteger(key));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(long key) {
        return Optional.ofNullable(getUUID(key));
    }

    @NotNull
    public <T> Optional<T> getOptional(long key, Decoder<? extends T> codec) {
        var data = this.value.get(key);
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(long key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(key);
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    public Set<Long2ObjectMap.Entry<Data>> entrySet() {
        return this.value.long2ObjectEntrySet();
    }

    @Override
    public void write(ByteBuf stream) {
        Data.writeVarInt(stream, value.size());
        this.value.forEach((k, v) -> {
            Data.writeVarLong(stream, k);
            Data.writeData(stream, v);
        });
    }

    @Override
    public byte getId() {
        return LONG_MAP;
    }

    @Override
    public Data copy() {
        var copy = new Long2ObjectOpenHashMap<Data>(this.value.size());
        Long2ObjectMaps.fastForEach(this.value, e -> copy.put(e.getLongKey(), e.getValue().copy()));
        return new LongMapData(copy);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof LongMapData(
                Long2ObjectMap<Data> value1
        ) && (this.value == value1 || this.value.equals(value1)));
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
