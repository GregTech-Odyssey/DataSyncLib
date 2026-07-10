package com.gto.datasynclib.datasream.data;

import com.gto.datasynclib.datasream.codec.DataDecoder;
import com.gto.datasynclib.datasream.codec.DataEncoder;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record IntMapData(Int2ObjectMap<Data> value) implements MapData {

    public static final IntMapData EMPTY = new IntMapData(Int2ObjectMaps.emptyMap());

    public IntMapData() {
        this(new Int2ObjectOpenHashMap<>());
    }

    public IntMapData(int initialCapacity) {
        this(new Int2ObjectOpenHashMap<>(initialCapacity));
    }

    public static IntMapData read(ByteBuf stream) {
        var size = Data.readVarInt(stream);
        var map = new Int2ObjectOpenHashMap<Data>(size);
        for (int i = 0; i < size; i++) {
            map.put(Data.readVarInt(stream), Data.readData(stream));
        }
        return new IntMapData(map);
    }

    public IntMapData shallowCopy() {
        return new IntMapData(new Int2ObjectOpenHashMap<>(this.value));
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

    public boolean containsKey(int key) {
        return this.value.containsKey(key);
    }

    public Data remove(int key) {
        return this.value.remove(key);
    }

    public <T> Data put(int key, Encoder<? super T> codec, T data) {
        return this.value.put(key, codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(int key, DataEncoder<? super T> codec, T data) {
        return this.value.put(key, codec.encode(data));
    }

    public Data put(int key, Data data) {
        return this.value.put(key, data);
    }

    public void putBoolean(int key, boolean data) {
        this.value.put(key, ByteData.valueOf(data));
    }

    public void putByte(int key, byte data) {
        this.value.put(key, ByteData.valueOf(data));
    }

    public void putShort(int key, short data) {
        this.value.put(key, ShortData.valueOf(data));
    }

    public void putChar(int key, char data) {
        this.value.put(key, CharData.valueOf(data));
    }

    public void putInt(int key, int data) {
        this.value.put(key, IntData.valueOf(data));
    }

    public void putLong(int key, long data) {
        this.value.put(key, LongData.valueOf(data));
    }

    public void putFloat(int key, float data) {
        this.value.put(key, FloatData.valueOf(data));
    }

    public void putDouble(int key, double data) {
        this.value.put(key, DoubleData.valueOf(data));
    }

    public void putString(int key, String data) {
        this.value.put(key, StringData.valueOf(data));
    }

    public void putUUID(int key, UUID data) {
        this.value.put(key, Data.valueOf(data));
    }

    public void putBigInteger(int key, BigInteger data) {
        this.value.put(key, Data.valueOf(data));
    }

    public boolean getBoolean(int key) {
        var data = this.value.get(key);
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(int key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(int key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(int key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(int key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(int key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(int key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(int key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getDouble();
    }

    @Nullable
    public Data get(int key) {
        return this.value.get(key);
    }

    @NotNull
    public ListData getList(int key) {
        if (this.value.get(key) instanceof ListData listData) {
            return listData;
        }
        return ListData.EMPTY;
    }

    @NotNull
    public IntMapData getMap(int key) {
        if (this.value.get(key) instanceof IntMapData mapData) {
            return mapData;
        }
        return IntMapData.EMPTY;
    }

    @Nullable
    public String getString(int key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getString();
    }

    @Nullable
    public BigInteger getBigInteger(int key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getBigInteger();
    }

    @Nullable
    public UUID getUUID(int key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getUUID();
    }

    @Nullable
    public <T> T get(int key, Decoder<? extends T> codec) {
        var data = this.value.get(key);
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(int key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(key);
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @NotNull
    public Optional<Data> getOptional(int key) {
        return Optional.ofNullable(this.value.get(key));
    }

    @NotNull
    public Optional<String> getOptionalString(int key) {
        return Optional.ofNullable(getString(key));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(int key) {
        return Optional.ofNullable(getBigInteger(key));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(int key) {
        return Optional.ofNullable(getUUID(key));
    }

    @NotNull
    public <T> Optional<T> getOptional(int key, Decoder<? extends T> codec) {
        var data = this.value.get(key);
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(int key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(key);
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    public Set<Int2ObjectMap.Entry<Data>> entrySet() {
        return this.value.int2ObjectEntrySet();
    }

    @Override
    public void write(ByteBuf stream) {
        Data.writeVarInt(stream, value.size());
        this.value.forEach((k, v) -> {
            Data.writeVarInt(stream, k);
            Data.writeData(stream, v);
        });
    }

    @Override
    public byte getId() {
        return INT_MAP;
    }

    @Override
    public Data copy() {
        var copy = new Int2ObjectOpenHashMap<Data>(this.value.size());
        Int2ObjectMaps.fastForEach(this.value, e -> copy.put(e.getIntKey(), e.getValue().copy()));
        return new IntMapData(copy);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof IntMapData(
                Int2ObjectMap<Data> value1
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
