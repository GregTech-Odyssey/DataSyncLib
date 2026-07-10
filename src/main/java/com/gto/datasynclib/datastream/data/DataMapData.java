package com.gto.datasynclib.datastream.data;

import com.gto.datasynclib.datastream.codec.DataDecoder;
import com.gto.datasynclib.datastream.codec.DataEncoder;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.*;

/**
 * Represents a Data-to-Data map in the Data type system. Wire format ID: 16.
 */
public record DataMapData(Map<Data, Data> value) implements MapData {

    public static final DataMapData EMPTY = new DataMapData(Collections.emptyMap());

    public DataMapData() {
        this(new HashMap<>());
    }

    public DataMapData(int initialCapacity) {
        this(new HashMap<>(initialCapacity));
    }

    public static DataMapData read(ByteBuf stream) {
        var size = Data.readVarInt(stream);
        var map = new HashMap<Data, Data>(size);
        for (int i = 0; i < size; i++) {
            map.put(Data.readData(stream), Data.readData(stream));
        }
        return new DataMapData(map);
    }

    public DataMapData shallowCopy() {
        return new DataMapData(new HashMap<>(this.value));
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

    public boolean containsKey(Data key) {
        return this.value.containsKey(key);
    }

    public boolean containsKey(boolean key) {
        return this.value.containsKey(ByteData.valueOf(key));
    }

    public boolean containsKey(byte key) {
        return this.value.containsKey(ByteData.valueOf(key));
    }

    public boolean containsKey(short key) {
        return this.value.containsKey(ShortData.valueOf(key));
    }

    public boolean containsKey(char key) {
        return this.value.containsKey(CharData.valueOf(key));
    }

    public boolean containsKey(int key) {
        return this.value.containsKey(IntData.valueOf(key));
    }

    public boolean containsKey(long key) {
        return this.value.containsKey(LongData.valueOf(key));
    }

    public boolean containsKey(float key) {
        return this.value.containsKey(FloatData.valueOf(key));
    }

    public boolean containsKey(double key) {
        return this.value.containsKey(DoubleData.valueOf(key));
    }

    public boolean containsKey(String key) {
        return this.value.containsKey(StringData.valueOf(key));
    }

    public boolean containsKey(UUID key) {
        return this.value.containsKey(Data.valueOf(key));
    }

    public boolean containsKey(BigInteger key) {
        return this.value.containsKey(Data.valueOf(key));
    }

    public Data remove(boolean key) {
        return this.value.remove(ByteData.valueOf(key));
    }

    public Data remove(byte key) {
        return this.value.remove(ByteData.valueOf(key));
    }

    public Data remove(short key) {
        return this.value.remove(ShortData.valueOf(key));
    }

    public Data remove(char key) {
        return this.value.remove(CharData.valueOf(key));
    }

    public Data remove(int key) {
        return this.value.remove(IntData.valueOf(key));
    }

    public Data remove(long key) {
        return this.value.remove(LongData.valueOf(key));
    }

    public Data remove(float key) {
        return this.value.remove(FloatData.valueOf(key));
    }

    public Data remove(double key) {
        return this.value.remove(DoubleData.valueOf(key));
    }

    public Data remove(String key) {
        return this.value.remove(StringData.valueOf(key));
    }

    public Data remove(UUID key) {
        return this.value.remove(Data.valueOf(key));
    }

    public Data remove(BigInteger key) {
        return this.value.remove(Data.valueOf(key));
    }

    public Data put(boolean key, Data data) {
        return this.value.put(ByteData.valueOf(key), data);
    }

    public Data put(byte key, Data data) {
        return this.value.put(ByteData.valueOf(key), data);
    }

    public Data put(short key, Data data) {
        return this.value.put(ShortData.valueOf(key), data);
    }

    public Data put(char key, Data data) {
        return this.value.put(CharData.valueOf(key), data);
    }

    public Data put(int key, Data data) {
        return this.value.put(IntData.valueOf(key), data);
    }

    public Data put(long key, Data data) {
        return this.value.put(LongData.valueOf(key), data);
    }

    public Data put(float key, Data data) {
        return this.value.put(FloatData.valueOf(key), data);
    }

    public Data put(double key, Data data) {
        return this.value.put(DoubleData.valueOf(key), data);
    }

    public Data put(String key, Data data) {
        return this.value.put(StringData.valueOf(key), data);
    }

    public Data put(UUID key, Data data) {
        return this.value.put(Data.valueOf(key), data);
    }

    public Data put(BigInteger key, Data data) {
        return this.value.put(Data.valueOf(key), data);
    }

    public <T> Data put(boolean key, Encoder<? super T> codec, T data) {
        return this.value.put(ByteData.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(byte key, Encoder<? super T> codec, T data) {
        return this.value.put(ByteData.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(short key, Encoder<? super T> codec, T data) {
        return this.value.put(ShortData.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(char key, Encoder<? super T> codec, T data) {
        return this.value.put(CharData.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(int key, Encoder<? super T> codec, T data) {
        return this.value.put(IntData.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(long key, Encoder<? super T> codec, T data) {
        return this.value.put(LongData.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(float key, Encoder<? super T> codec, T data) {
        return this.value.put(FloatData.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(double key, Encoder<? super T> codec, T data) {
        return this.value.put(DoubleData.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(String key, Encoder<? super T> codec, T data) {
        return this.value.put(StringData.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(UUID key, Encoder<? super T> codec, T data) {
        return this.value.put(Data.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(BigInteger key, Encoder<? super T> codec, T data) {
        return this.value.put(Data.valueOf(key), codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> Data put(boolean key, DataEncoder<? super T> codec, T data) {
        return this.value.put(ByteData.valueOf(key), codec.encode(data));
    }

    public <T> Data put(byte key, DataEncoder<? super T> codec, T data) {
        return this.value.put(ByteData.valueOf(key), codec.encode(data));
    }

    public <T> Data put(short key, DataEncoder<? super T> codec, T data) {
        return this.value.put(ShortData.valueOf(key), codec.encode(data));
    }

    public <T> Data put(char key, DataEncoder<? super T> codec, T data) {
        return this.value.put(CharData.valueOf(key), codec.encode(data));
    }

    public <T> Data put(int key, DataEncoder<? super T> codec, T data) {
        return this.value.put(IntData.valueOf(key), codec.encode(data));
    }

    public <T> Data put(long key, DataEncoder<? super T> codec, T data) {
        return this.value.put(LongData.valueOf(key), codec.encode(data));
    }

    public <T> Data put(float key, DataEncoder<? super T> codec, T data) {
        return this.value.put(FloatData.valueOf(key), codec.encode(data));
    }

    public <T> Data put(double key, DataEncoder<? super T> codec, T data) {
        return this.value.put(DoubleData.valueOf(key), codec.encode(data));
    }

    public <T> Data put(String key, DataEncoder<? super T> codec, T data) {
        return this.value.put(StringData.valueOf(key), codec.encode(data));
    }

    public <T> Data put(UUID key, DataEncoder<? super T> codec, T data) {
        return this.value.put(Data.valueOf(key), codec.encode(data));
    }

    public <T> Data put(BigInteger key, DataEncoder<? super T> codec, T data) {
        return this.value.put(Data.valueOf(key), codec.encode(data));
    }

    public void putBoolean(boolean key, boolean data) {
        this.value.put(ByteData.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(boolean key, byte data) {
        this.value.put(ByteData.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(boolean key, short data) {
        this.value.put(ByteData.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(boolean key, char data) {
        this.value.put(ByteData.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(boolean key, int data) {
        this.value.put(ByteData.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(boolean key, long data) {
        this.value.put(ByteData.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(boolean key, float data) {
        this.value.put(ByteData.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(boolean key, double data) {
        this.value.put(ByteData.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(boolean key, String data) {
        this.value.put(ByteData.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(boolean key, UUID data) {
        this.value.put(ByteData.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(boolean key, BigInteger data) {
        this.value.put(ByteData.valueOf(key), Data.valueOf(data));
    }

    public void putBoolean(byte key, boolean data) {
        this.value.put(ByteData.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(byte key, byte data) {
        this.value.put(ByteData.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(byte key, short data) {
        this.value.put(ByteData.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(byte key, char data) {
        this.value.put(ByteData.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(byte key, int data) {
        this.value.put(ByteData.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(byte key, long data) {
        this.value.put(ByteData.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(byte key, float data) {
        this.value.put(ByteData.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(byte key, double data) {
        this.value.put(ByteData.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(byte key, String data) {
        this.value.put(ByteData.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(byte key, UUID data) {
        this.value.put(ByteData.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(byte key, BigInteger data) {
        this.value.put(ByteData.valueOf(key), Data.valueOf(data));
    }

    public void putBoolean(short key, boolean data) {
        this.value.put(ShortData.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(short key, byte data) {
        this.value.put(ShortData.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(short key, short data) {
        this.value.put(ShortData.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(short key, char data) {
        this.value.put(ShortData.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(short key, int data) {
        this.value.put(ShortData.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(short key, long data) {
        this.value.put(ShortData.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(short key, float data) {
        this.value.put(ShortData.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(short key, double data) {
        this.value.put(ShortData.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(short key, String data) {
        this.value.put(ShortData.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(short key, UUID data) {
        this.value.put(ShortData.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(short key, BigInteger data) {
        this.value.put(ShortData.valueOf(key), Data.valueOf(data));
    }

    public void putBoolean(char key, boolean data) {
        this.value.put(CharData.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(char key, byte data) {
        this.value.put(CharData.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(char key, short data) {
        this.value.put(CharData.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(char key, char data) {
        this.value.put(CharData.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(char key, int data) {
        this.value.put(CharData.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(char key, long data) {
        this.value.put(CharData.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(char key, float data) {
        this.value.put(CharData.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(char key, double data) {
        this.value.put(CharData.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(char key, String data) {
        this.value.put(CharData.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(char key, UUID data) {
        this.value.put(CharData.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(char key, BigInteger data) {
        this.value.put(CharData.valueOf(key), Data.valueOf(data));
    }

    public void putBoolean(int key, boolean data) {
        this.value.put(IntData.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(int key, byte data) {
        this.value.put(IntData.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(int key, short data) {
        this.value.put(IntData.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(int key, char data) {
        this.value.put(IntData.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(int key, int data) {
        this.value.put(IntData.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(int key, long data) {
        this.value.put(IntData.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(int key, float data) {
        this.value.put(IntData.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(int key, double data) {
        this.value.put(IntData.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(int key, String data) {
        this.value.put(IntData.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(int key, UUID data) {
        this.value.put(IntData.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(int key, BigInteger data) {
        this.value.put(IntData.valueOf(key), Data.valueOf(data));
    }

    public void putBoolean(long key, boolean data) {
        this.value.put(LongData.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(long key, byte data) {
        this.value.put(LongData.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(long key, short data) {
        this.value.put(LongData.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(long key, char data) {
        this.value.put(LongData.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(long key, int data) {
        this.value.put(LongData.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(long key, long data) {
        this.value.put(LongData.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(long key, float data) {
        this.value.put(LongData.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(long key, double data) {
        this.value.put(LongData.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(long key, String data) {
        this.value.put(LongData.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(long key, UUID data) {
        this.value.put(LongData.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(long key, BigInteger data) {
        this.value.put(LongData.valueOf(key), Data.valueOf(data));
    }

    public void putBoolean(float key, boolean data) {
        this.value.put(FloatData.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(float key, byte data) {
        this.value.put(FloatData.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(float key, short data) {
        this.value.put(FloatData.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(float key, char data) {
        this.value.put(FloatData.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(float key, int data) {
        this.value.put(FloatData.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(float key, long data) {
        this.value.put(FloatData.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(float key, float data) {
        this.value.put(FloatData.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(float key, double data) {
        this.value.put(FloatData.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(float key, String data) {
        this.value.put(FloatData.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(float key, UUID data) {
        this.value.put(FloatData.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(float key, BigInteger data) {
        this.value.put(FloatData.valueOf(key), Data.valueOf(data));
    }

    public void putBoolean(double key, boolean data) {
        this.value.put(DoubleData.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(double key, byte data) {
        this.value.put(DoubleData.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(double key, short data) {
        this.value.put(DoubleData.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(double key, char data) {
        this.value.put(DoubleData.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(double key, int data) {
        this.value.put(DoubleData.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(double key, long data) {
        this.value.put(DoubleData.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(double key, float data) {
        this.value.put(DoubleData.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(double key, double data) {
        this.value.put(DoubleData.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(double key, String data) {
        this.value.put(DoubleData.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(double key, UUID data) {
        this.value.put(DoubleData.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(double key, BigInteger data) {
        this.value.put(DoubleData.valueOf(key), Data.valueOf(data));
    }

    public void putBoolean(String key, boolean data) {
        this.value.put(StringData.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(String key, byte data) {
        this.value.put(StringData.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(String key, short data) {
        this.value.put(StringData.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(String key, char data) {
        this.value.put(StringData.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(String key, int data) {
        this.value.put(StringData.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(String key, long data) {
        this.value.put(StringData.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(String key, float data) {
        this.value.put(StringData.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(String key, double data) {
        this.value.put(StringData.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(String key, String data) {
        this.value.put(StringData.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(String key, UUID data) {
        this.value.put(StringData.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(String key, BigInteger data) {
        this.value.put(StringData.valueOf(key), Data.valueOf(data));
    }

    public void putBoolean(UUID key, boolean data) {
        this.value.put(Data.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(UUID key, byte data) {
        this.value.put(Data.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(UUID key, short data) {
        this.value.put(Data.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(UUID key, char data) {
        this.value.put(Data.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(UUID key, int data) {
        this.value.put(Data.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(UUID key, long data) {
        this.value.put(Data.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(UUID key, float data) {
        this.value.put(Data.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(UUID key, double data) {
        this.value.put(Data.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(UUID key, String data) {
        this.value.put(Data.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(UUID key, UUID data) {
        this.value.put(Data.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(UUID key, BigInteger data) {
        this.value.put(Data.valueOf(key), Data.valueOf(data));
    }

    public void putBoolean(BigInteger key, boolean data) {
        this.value.put(Data.valueOf(key), ByteData.valueOf(data));
    }

    public void putByte(BigInteger key, byte data) {
        this.value.put(Data.valueOf(key), ByteData.valueOf(data));
    }

    public void putShort(BigInteger key, short data) {
        this.value.put(Data.valueOf(key), ShortData.valueOf(data));
    }

    public void putChar(BigInteger key, char data) {
        this.value.put(Data.valueOf(key), CharData.valueOf(data));
    }

    public void putInt(BigInteger key, int data) {
        this.value.put(Data.valueOf(key), IntData.valueOf(data));
    }

    public void putLong(BigInteger key, long data) {
        this.value.put(Data.valueOf(key), LongData.valueOf(data));
    }

    public void putFloat(BigInteger key, float data) {
        this.value.put(Data.valueOf(key), FloatData.valueOf(data));
    }

    public void putDouble(BigInteger key, double data) {
        this.value.put(Data.valueOf(key), DoubleData.valueOf(data));
    }

    public void putString(BigInteger key, String data) {
        this.value.put(Data.valueOf(key), StringData.valueOf(data));
    }

    public void putUUID(BigInteger key, UUID data) {
        this.value.put(Data.valueOf(key), Data.valueOf(data));
    }

    public void putBigInteger(BigInteger key, BigInteger data) {
        this.value.put(Data.valueOf(key), Data.valueOf(data));
    }

    @Nullable
    public Data get(boolean key) {
        return this.value.get(ByteData.valueOf(key));
    }

    @Nullable
    public Data get(byte key) {
        return this.value.get(ByteData.valueOf(key));
    }

    @Nullable
    public Data get(short key) {
        return this.value.get(ShortData.valueOf(key));
    }

    @Nullable
    public Data get(char key) {
        return this.value.get(CharData.valueOf(key));
    }

    @Nullable
    public Data get(int key) {
        return this.value.get(IntData.valueOf(key));
    }

    @Nullable
    public Data get(long key) {
        return this.value.get(LongData.valueOf(key));
    }

    @Nullable
    public Data get(float key) {
        return this.value.get(FloatData.valueOf(key));
    }

    @Nullable
    public Data get(double key) {
        return this.value.get(DoubleData.valueOf(key));
    }

    @Nullable
    public Data get(String key) {
        return this.value.get(StringData.valueOf(key));
    }

    @Nullable
    public Data get(UUID key) {
        return this.value.get(Data.valueOf(key));
    }

    @Nullable
    public Data get(BigInteger key) {
        return this.value.get(Data.valueOf(key));
    }

    @Nullable
    public <T> T get(boolean key, Decoder<? extends T> codec) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(byte key, Decoder<? extends T> codec) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(short key, Decoder<? extends T> codec) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(char key, Decoder<? extends T> codec) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(int key, Decoder<? extends T> codec) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(long key, Decoder<? extends T> codec) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(float key, Decoder<? extends T> codec) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(double key, Decoder<? extends T> codec) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(String key, Decoder<? extends T> codec) {
        var data = this.value.get(StringData.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(UUID key, Decoder<? extends T> codec) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(BigInteger key, Decoder<? extends T> codec) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(boolean key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @Nullable
    public <T> T get(byte key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @Nullable
    public <T> T get(short key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @Nullable
    public <T> T get(char key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @Nullable
    public <T> T get(int key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @Nullable
    public <T> T get(long key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @Nullable
    public <T> T get(float key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @Nullable
    public <T> T get(double key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @Nullable
    public <T> T get(String key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(StringData.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @Nullable
    public <T> T get(UUID key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @Nullable
    public <T> T get(BigInteger key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    public boolean getBoolean(boolean key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(boolean key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(boolean key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(boolean key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(boolean key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(boolean key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(boolean key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(boolean key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getDouble();
    }

    public boolean getBoolean(byte key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(byte key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(byte key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(byte key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(byte key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(byte key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(byte key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(byte key) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return 0;
        return data.getDouble();
    }

    public boolean getBoolean(short key) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(short key) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(short key) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(short key) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(short key) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(short key) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(short key) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(short key) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return 0;
        return data.getDouble();
    }

    public boolean getBoolean(char key) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(char key) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(char key) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(char key) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(char key) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(char key) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(char key) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(char key) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return 0;
        return data.getDouble();
    }

    public boolean getBoolean(int key) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(int key) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(int key) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(int key) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(int key) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(int key) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(int key) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(int key) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return 0;
        return data.getDouble();
    }

    public boolean getBoolean(long key) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(long key) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(long key) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(long key) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(long key) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(long key) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(long key) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(long key) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return 0;
        return data.getDouble();
    }

    public boolean getBoolean(float key) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(float key) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(float key) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(float key) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(float key) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(float key) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(float key) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(float key) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return 0;
        return data.getDouble();
    }

    public boolean getBoolean(double key) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(double key) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(double key) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(double key) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(double key) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(double key) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(double key) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(double key) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return 0;
        return data.getDouble();
    }

    @Nullable
    public String getString(String key) {
        var data = this.value.get(StringData.valueOf(key));
        if (data == null) return null;
        return data.getString();
    }

    @Nullable
    public BigInteger getBigInteger(String key) {
        var data = this.value.get(StringData.valueOf(key));
        if (data == null) return null;
        return data.getBigInteger();
    }

    @Nullable
    public UUID getUUID(String key) {
        var data = this.value.get(StringData.valueOf(key));
        if (data == null) return null;
        return data.getUUID();
    }

    @NotNull
    public ListData getList(String key) {
        if (this.value.get(StringData.valueOf(key)) instanceof ListData listData) {
            return listData;
        }
        return ListData.EMPTY;
    }

    @NotNull
    public DataMapData getMap(String key) {
        if (this.value.get(StringData.valueOf(key)) instanceof DataMapData mapData) {
            return mapData;
        }
        return DataMapData.EMPTY;
    }

    @Nullable
    public String getString(UUID key) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return null;
        return data.getString();
    }

    @Nullable
    public BigInteger getBigInteger(UUID key) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return null;
        return data.getBigInteger();
    }

    @Nullable
    public UUID getUUID(UUID key) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return null;
        return data.getUUID();
    }

    @NotNull
    public ListData getList(UUID key) {
        if (this.value.get(Data.valueOf(key)) instanceof ListData listData) {
            return listData;
        }
        return ListData.EMPTY;
    }

    @NotNull
    public DataMapData getMap(UUID key) {
        if (this.value.get(Data.valueOf(key)) instanceof DataMapData mapData) {
            return mapData;
        }
        return DataMapData.EMPTY;
    }

    @Nullable
    public String getString(BigInteger key) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return null;
        return data.getString();
    }

    @Nullable
    public BigInteger getBigInteger(BigInteger key) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return null;
        return data.getBigInteger();
    }

    @Nullable
    public UUID getUUID(BigInteger key) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return null;
        return data.getUUID();
    }

    @NotNull
    public ListData getList(BigInteger key) {
        if (this.value.get(Data.valueOf(key)) instanceof ListData listData) {
            return listData;
        }
        return ListData.EMPTY;
    }

    @NotNull
    public DataMapData getMap(BigInteger key) {
        if (this.value.get(Data.valueOf(key)) instanceof DataMapData mapData) {
            return mapData;
        }
        return DataMapData.EMPTY;
    }

    @NotNull
    public Optional<Data> getOptional(boolean key) {
        return Optional.ofNullable(this.value.get(ByteData.valueOf(key)));
    }

    @NotNull
    public Optional<Data> getOptional(byte key) {
        return Optional.ofNullable(this.value.get(ByteData.valueOf(key)));
    }

    @NotNull
    public Optional<Data> getOptional(short key) {
        return Optional.ofNullable(this.value.get(ShortData.valueOf(key)));
    }

    @NotNull
    public Optional<Data> getOptional(char key) {
        return Optional.ofNullable(this.value.get(CharData.valueOf(key)));
    }

    @NotNull
    public Optional<Data> getOptional(int key) {
        return Optional.ofNullable(this.value.get(IntData.valueOf(key)));
    }

    @NotNull
    public Optional<Data> getOptional(long key) {
        return Optional.ofNullable(this.value.get(LongData.valueOf(key)));
    }

    @NotNull
    public Optional<Data> getOptional(float key) {
        return Optional.ofNullable(this.value.get(FloatData.valueOf(key)));
    }

    @NotNull
    public Optional<Data> getOptional(double key) {
        return Optional.ofNullable(this.value.get(DoubleData.valueOf(key)));
    }

    @NotNull
    public Optional<Data> getOptional(String key) {
        return Optional.ofNullable(this.value.get(StringData.valueOf(key)));
    }

    @NotNull
    public Optional<Data> getOptional(UUID key) {
        return Optional.ofNullable(this.value.get(Data.valueOf(key)));
    }

    @NotNull
    public Optional<Data> getOptional(BigInteger key) {
        return Optional.ofNullable(this.value.get(Data.valueOf(key)));
    }

    @NotNull
    public Optional<String> getOptionalString(boolean key) {
        return Optional.ofNullable(getString(ByteData.valueOf(key)));
    }

    @NotNull
    public Optional<String> getOptionalString(byte key) {
        return Optional.ofNullable(getString(ByteData.valueOf(key)));
    }

    @NotNull
    public Optional<String> getOptionalString(short key) {
        return Optional.ofNullable(getString(ShortData.valueOf(key)));
    }

    @NotNull
    public Optional<String> getOptionalString(char key) {
        return Optional.ofNullable(getString(CharData.valueOf(key)));
    }

    @NotNull
    public Optional<String> getOptionalString(int key) {
        return Optional.ofNullable(getString(IntData.valueOf(key)));
    }

    @NotNull
    public Optional<String> getOptionalString(long key) {
        return Optional.ofNullable(getString(LongData.valueOf(key)));
    }

    @NotNull
    public Optional<String> getOptionalString(float key) {
        return Optional.ofNullable(getString(FloatData.valueOf(key)));
    }

    @NotNull
    public Optional<String> getOptionalString(double key) {
        return Optional.ofNullable(getString(DoubleData.valueOf(key)));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(boolean key) {
        return Optional.ofNullable(getBigInteger(ByteData.valueOf(key)));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(byte key) {
        return Optional.ofNullable(getBigInteger(ByteData.valueOf(key)));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(short key) {
        return Optional.ofNullable(getBigInteger(ShortData.valueOf(key)));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(char key) {
        return Optional.ofNullable(getBigInteger(CharData.valueOf(key)));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(int key) {
        return Optional.ofNullable(getBigInteger(IntData.valueOf(key)));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(long key) {
        return Optional.ofNullable(getBigInteger(LongData.valueOf(key)));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(float key) {
        return Optional.ofNullable(getBigInteger(FloatData.valueOf(key)));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(double key) {
        return Optional.ofNullable(getBigInteger(DoubleData.valueOf(key)));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(boolean key) {
        return Optional.ofNullable(getUUID(ByteData.valueOf(key)));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(byte key) {
        return Optional.ofNullable(getUUID(ByteData.valueOf(key)));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(short key) {
        return Optional.ofNullable(getUUID(ShortData.valueOf(key)));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(char key) {
        return Optional.ofNullable(getUUID(CharData.valueOf(key)));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(int key) {
        return Optional.ofNullable(getUUID(IntData.valueOf(key)));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(long key) {
        return Optional.ofNullable(getUUID(LongData.valueOf(key)));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(float key) {
        return Optional.ofNullable(getUUID(FloatData.valueOf(key)));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(double key) {
        return Optional.ofNullable(getUUID(DoubleData.valueOf(key)));
    }

    @NotNull
    public <T> Optional<T> getOptional(boolean key, Decoder<? extends T> codec) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(byte key, Decoder<? extends T> codec) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(short key, Decoder<? extends T> codec) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(char key, Decoder<? extends T> codec) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(int key, Decoder<? extends T> codec) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(long key, Decoder<? extends T> codec) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(float key, Decoder<? extends T> codec) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(double key, Decoder<? extends T> codec) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(String key, Decoder<? extends T> codec) {
        var data = this.value.get(StringData.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(UUID key, Decoder<? extends T> codec) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(BigInteger key, Decoder<? extends T> codec) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(boolean key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    @NotNull
    public <T> Optional<T> getOptional(byte key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(ByteData.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    @NotNull
    public <T> Optional<T> getOptional(short key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(ShortData.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    @NotNull
    public <T> Optional<T> getOptional(char key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(CharData.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    @NotNull
    public <T> Optional<T> getOptional(int key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(IntData.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    @NotNull
    public <T> Optional<T> getOptional(long key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(LongData.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    @NotNull
    public <T> Optional<T> getOptional(float key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(FloatData.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    @NotNull
    public <T> Optional<T> getOptional(double key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(DoubleData.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    @NotNull
    public <T> Optional<T> getOptional(String key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(StringData.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    @NotNull
    public <T> Optional<T> getOptional(UUID key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    @NotNull
    public <T> Optional<T> getOptional(BigInteger key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(Data.valueOf(key));
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    public Data put(Data key, Data data) {
        return this.value.put(key, data);
    }

    public void putBoolean(Data key, boolean data) {
        this.value.put(key, ByteData.valueOf(data));
    }

    public void putByte(Data key, byte data) {
        this.value.put(key, ByteData.valueOf(data));
    }

    public void putShort(Data key, short data) {
        this.value.put(key, ShortData.valueOf(data));
    }

    public void putChar(Data key, char data) {
        this.value.put(key, CharData.valueOf(data));
    }

    public void putInt(Data key, int data) {
        this.value.put(key, IntData.valueOf(data));
    }

    public void putLong(Data key, long data) {
        this.value.put(key, LongData.valueOf(data));
    }

    public void putFloat(Data key, float data) {
        this.value.put(key, FloatData.valueOf(data));
    }

    public void putDouble(Data key, double data) {
        this.value.put(key, DoubleData.valueOf(data));
    }

    public void putString(Data key, String data) {
        this.value.put(key, StringData.valueOf(data));
    }

    public void putUUID(Data key, UUID data) {
        this.value.put(key, Data.valueOf(data));
    }

    public void putBigInteger(Data key, BigInteger data) {
        this.value.put(key, Data.valueOf(data));
    }

    public boolean getBoolean(Data key) {
        var data = this.value.get(key);
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(Data key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(Data key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(Data key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(Data key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(Data key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(Data key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(Data key) {
        var data = this.value.get(key);
        if (data == null) return 0;
        return data.getDouble();
    }

    @Nullable
    public Data get(Data key) {
        return this.value.get(key);
    }

    @NotNull
    public ListData getList(Data key) {
        if (this.value.get(key) instanceof ListData listData) {
            return listData;
        }
        return ListData.EMPTY;
    }

    @NotNull
    public DataMapData getMap(Data key) {
        if (this.value.get(key) instanceof DataMapData mapData) {
            return mapData;
        }
        return DataMapData.EMPTY;
    }

    @Nullable
    public String getString(Data key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getString();
    }

    @Nullable
    public BigInteger getBigInteger(Data key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getBigInteger();
    }

    @Nullable
    public UUID getUUID(Data key) {
        var data = this.value.get(key);
        if (data == null) return null;
        return data.getUUID();
    }

    @Nullable
    public <T> T get(Data key, Decoder<? extends T> codec) {
        var data = this.value.get(key);
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(Data key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(key);
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    @NotNull
    public Optional<Data> getOptional(Data key) {
        return Optional.ofNullable(this.value.get(key));
    }

    @NotNull
    public Optional<String> getOptionalString(Data key) {
        return Optional.ofNullable(getString(key));
    }

    @NotNull
    public Optional<BigInteger> getOptionalBigInteger(Data key) {
        return Optional.ofNullable(getBigInteger(key));
    }

    @NotNull
    public Optional<UUID> getOptionalUUID(Data key) {
        return Optional.ofNullable(getUUID(key));
    }

    @NotNull
    public <T> Optional<T> getOptional(Data key, Decoder<? extends T> codec) {
        var data = this.value.get(key);
        if (data == null) return Optional.empty();
        return codec.decode(DataOps.INSTANCE, data).result().map(Pair::getFirst);
    }

    @NotNull
    public <T> Optional<T> getOptional(Data key, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(key);
        if (data == null) return Optional.empty();
        return Optional.ofNullable(codec.decode(data, dataVersion));
    }

    public Set<Map.Entry<Data, Data>> entrySet() {
        return this.value.entrySet();
    }

    @Override
    public @NotNull Map<Data, Data> getDataMap() {
        return this.value;
    }

    @Override
    public void write(ByteBuf stream) {
        Data.writeVarInt(stream, value.size());
        this.value.forEach((k, v) -> {
            Data.writeData(stream, k);
            Data.writeData(stream, v);
        });
    }

    @Override
    public byte getId() {
        return DATA_MAP;
    }

    @Override
    public Data copy() {
        var copy = new HashMap<Data, Data>(this.value.size());
        this.value.forEach((k, v) -> copy.put(k.copy(), v.copy()));
        return new DataMapData(copy);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof DataMapData(
                Map<Data, Data> value1
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
