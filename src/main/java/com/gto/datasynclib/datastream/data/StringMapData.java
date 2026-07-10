package com.gto.datasynclib.datastream.data;

import com.gto.datasynclib.datastream.codec.ByteStreamCodec;
import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.datastream.codec.DataDecoder;
import com.gto.datasynclib.datastream.codec.DataEncoder;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.*;

/**
 * Represents a String-to-Data map in the Data type system. Wire format ID: 14.
 * Primary container for disk persistence of field data.
 */
public record StringMapData(Map<String, Data> value) implements MapData {

    public static final StringMapData EMPTY = new StringMapData(Collections.emptyMap());

    public static final ByteStreamCodec<StringMapData> BYTE_STREAM_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, StringMapData obj) {
            obj.write(stream);
        }

        @Override
        public StringMapData decode(FriendlyByteBuf stream) {
            return Data.readData(STRING_MAP, stream).asStringMapData();
        }

        static {
            ByteStreamCodec.registerCodec(StringMapData.class, BYTE_STREAM_CODEC);
        }
    };

    public static final DataCodec<StringMapData> DATA_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(StringMapData obj) {
            return obj;
        }

        @Override
        public StringMapData decode(@NotNull Data data, int dataVersion) {
            return (StringMapData) data;
        }

        static {
            DataCodec.registerCodec(StringMapData.class, DATA_CODEC);
        }
    };

    public StringMapData() {
        this(new HashMap<>());
    }

    public StringMapData(int initialCapacity) {
        this(new HashMap<>(initialCapacity));
    }

    public static StringMapData read(ByteBuf stream) {
        var size = Data.readVarInt(stream);
        var map = new HashMap<String, Data>(size);
        for (int i = 0; i < size; i++) {
            map.put(Data.readString(stream), Data.readData(stream));
        }
        return new StringMapData(map);
    }

    public StringMapData shallowCopy() {
        return new StringMapData(new HashMap<>(this.value));
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

    public boolean containsKey(String key) {
        return this.value.containsKey(key);
    }

    public Data remove(String key) {
        return this.value.remove(key);
    }

    public <T> Data put(String key, Encoder<? super T> codec, T data) {
        return this.value.put(key, codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(String key, DataEncoder<? super T> codec, T data) {
        return this.value.put(key, codec.encode(data));
    }

    public Data put(String key, Data data) {
        return this.value.put(key, data);
    }

    public void putBoolean(String key, boolean data) {
        this.value.put(key, ByteData.valueOf(data));
    }

    public void putByte(String key, byte data) {
        this.value.put(key, ByteData.valueOf(data));
    }

    public void putShort(String key, short data) {
        this.value.put(key, ShortData.valueOf(data));
    }

    public void putChar(String key, char data) {
        this.value.put(key, CharData.valueOf(data));
    }

    public void putInt(String key, int data) {
        this.value.put(key, IntData.valueOf(data));
    }

    public void putLong(String key, long data) {
        this.value.put(key, LongData.valueOf(data));
    }

    public void putFloat(String key, float data) {
        this.value.put(key, FloatData.valueOf(data));
    }

    public void putDouble(String key, double data) {
        this.value.put(key, DoubleData.valueOf(data));
    }

    public void putString(String key, String data) {
        this.value.put(key, StringData.valueOf(data));
    }

    public void putUUID(String key, UUID data) {
        this.value.put(key, Data.valueOf(data));
    }

    public void putBigInteger(String key, BigInteger data) {
        this.value.put(key, Data.valueOf(data));
    }

    public boolean getBoolean(String key) {
        var data = this.value.get(key);
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(String key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(String key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(String key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(String key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(String key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(String key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(String key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getDouble();
    }

    @Nullable
    public Data get(String key) {
        return this.value.get(key);
    }

    @NotNull
    public ListData getList(String key) {
        if (this.value.get(key) instanceof ListData listData) {
            return listData;
        }
        return ListData.EMPTY;
    }

    @NotNull
    public StringMapData getMap(String key) {
        if (this.value.get(key) instanceof StringMapData mapData) {
            return mapData;
        }
        return StringMapData.EMPTY;
    }

    @Nullable
    public String getString(String key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getString();
    }

    @Nullable
    public BigInteger getBigInteger(String key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getBigInteger();
    }

    @Nullable
    public UUID getUUID(String key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getUUID();
    }

    @Nullable
    public <T> T get(String key, Decoder<? extends T> codec) {
        var data = this.value.get(key);
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(String key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(key);
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @NotNull
    public Optional<Data> getOptional(String key) {
        return Optional.ofNullable(this.value.get(key));
    }

    @NotNull
    public Optional<String> getOptionalString(String key) {
        return Optional.ofNullable(getString(key));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(String key) {
        return Optional.ofNullable(getBigInteger(key));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(String key) {
        return Optional.ofNullable(getUUID(key));
    }

    @NotNull
    public <T> Optional<T> getOptional(String key, Decoder<? extends T> codec) {
        var data = this.value.get(key);
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(String key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(key);
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    public Set<Map.Entry<String, Data>> entrySet() {
        return this.value.entrySet();
    }

    @Override
    public @NotNull Map<String, Data> getStringMap() {
        return this.value;
    }

    @Override
    public void write(ByteBuf stream) {
        Data.writeVarInt(stream, value.size());
        this.value.forEach((k, v) -> {
            Data.writeString(stream, k);
            Data.writeData(stream, v);
        });
    }

    @Override
    public byte getId() {
        return STRING_MAP;
    }

    @Override
    public Data copy() {
        var copy = new HashMap<String, Data>(this.value.size());
        this.value.forEach((k, v) -> copy.put(k, v.copy()));
        return new StringMapData(copy);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof StringMapData(
                Map<String, Data> value1
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
