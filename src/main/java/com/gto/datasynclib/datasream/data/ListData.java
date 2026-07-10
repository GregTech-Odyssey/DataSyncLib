package com.gto.datasynclib.datasream.data;

import com.gto.datasynclib.datasream.codec.DataDecoder;
import com.gto.datasynclib.datasream.codec.DataEncoder;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;

public record ListData(List<Data> value) implements CollectionData, Iterable<Data> {

    public static final ListData EMPTY = new ListData(Collections.emptyList());

    public ListData() {
        this(new ArrayList<>());
    }

    public ListData(int initialCapacity) {
        this(new ArrayList<>(initialCapacity));
    }

    public static ListData read(ByteBuf stream) {
        var size = Data.readVarInt(stream);
        var list = new ArrayList<Data>(size);
        for (int i = 0; i < size; i++) {
            list.add(Data.readData(stream));
        }
        return new ListData(list);
    }

    public static ListData of(Data... datas) {
        return new ListData(new ArrayList<>(Arrays.asList(datas)));
    }

    public static ListData of(Collection<Data> datas) {
        return new ListData(new ArrayList<>(datas));
    }

    public ListData shallowCopy() {
        return new ListData(new ArrayList<>(value));
    }

    public void clear() {
        value.clear();
    }

    public void set(int index, Data data) {
        value.set(index, data);
    }

    public void add(int index, Data data) {
        value.add(index, data);
    }

    public void add(Data data) {
        value.add(data);
    }

    public void addNull() {
        value.add(NullData.INSTANCE);
    }

    public void addBoolean(boolean data) {
        this.value.add(ByteData.valueOf(data));
    }

    public void addByte(byte data) {
        this.value.add(ByteData.valueOf(data));
    }

    public void addShort(short data) {
        this.value.add(ShortData.valueOf(data));
    }

    public void addChar(char data) {
        this.value.add(CharData.valueOf(data));
    }

    public void addInt(int data) {
        this.value.add(IntData.valueOf(data));
    }

    public void addLong(long data) {
        this.value.add(LongData.valueOf(data));
    }

    public void addFloat(float data) {
        this.value.add(FloatData.valueOf(data));
    }

    public void addDouble(double data) {
        this.value.add(DoubleData.valueOf(data));
    }

    public void addString(String data) {
        this.value.add(StringData.valueOf(data));
    }

    public void addUUID(UUID data) {
        this.value.add(Data.valueOf(data));
    }

    public void addBigInteger(BigInteger data) {
        this.value.add(Data.valueOf(data));
    }

    public <T> void add(Encoder<? super T> codec, T data) {
        this.value.add(codec.encodeStart(DataOps.INSTANCE, data).result().orElseThrow());
    }

    public <T> void add(DataEncoder<? super T> codec, T data) {
        this.value.add(codec.encode(data));
    }

    public void addAll(List<Data> datas) {
        value.addAll(datas);
    }

    public void addAll(ListData data) {
        value.addAll(data.value);
    }

    public Data remove(int index) {
        return value.remove(index);
    }

    public Data get(int index) {
        return value.get(index);
    }

    public boolean getBoolean(int index) {
        var data = this.value.get(index);
        if (data == null) return false;
        return data.getBoolean();
    }

    public byte getByte(int index) {
        var data = this.value.get(index);
        if (data == null) return 0;
        return data.getByte();
    }

    public short getShort(int index) {
        var data = this.value.get(index);
        if (data == null) return 0;
        return data.getShort();
    }

    public char getChar(int index) {
        var data = this.value.get(index);
        if (data == null) return 0;
        return data.getChar();
    }

    public int getInt(int index) {
        var data = this.value.get(index);
        if (data == null) return 0;
        return data.getInt();
    }

    public long getLong(int index) {
        var data = this.value.get(index);
        if (data == null) return 0;
        return data.getLong();
    }

    public float getFloat(int index) {
        var data = this.value.get(index);
        if (data == null) return 0;
        return data.getFloat();
    }

    public double getDouble(int index) {
        var data = this.value.get(index);
        if (data == null) return 0;
        return data.getDouble();
    }

    @NotNull
    public ListData getList(int index) {
        if (this.value.get(index) instanceof ListData listData) {
            return listData;
        }
        return ListData.EMPTY;
    }

    @NotNull
    public StringMapData getMap(int index) {
        if (this.value.get(index) instanceof StringMapData mapData) {
            return mapData;
        }
        return StringMapData.EMPTY;
    }

    @Nullable
    public String getString(int index) {
        var data = this.value.get(index);
        if (data == null) return null;
        return data.getString();
    }

    @Nullable
    public BigInteger getBigInteger(int index) {
        var data = this.value.get(index);
        if (data == null) return null;
        return data.getBigInteger();
    }

    @Nullable
    public UUID getUUID(int index) {
        var data = this.value.get(index);
        if (data == null) return null;
        return data.getUUID();
    }

    @Nullable
    public <T> T get(int index, Decoder<? extends T> codec) {
        var data = this.value.get(index);
        if (data == null) return null;
        return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
    }

    @Nullable
    public <T> T get(int index, DataDecoder<? extends T> codec, int dataVersion) {
        var data = this.value.get(index);
        if (data == null) return null;
        return codec.decode(data, dataVersion);
    }

    public int size() {
        return value.size();
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public @NotNull List<Data> getList() {
        return value;
    }

    @Override
    public void write(ByteBuf stream) {
        Data.writeVarInt(stream, value.size());
        value.forEach(d -> Data.writeData(stream, d));
    }

    @Override
    public byte getId() {
        return LIST;
    }

    @Override
    public Data copy() {
        var size = value.size();
        var copy = new ArrayList<Data>(size);
        for (Data data : value) {
            copy.add(data.copy());
        }
        return new ListData(copy);
    }

    @Override
    public Iterator<Data> iterator() {
        return value.iterator();
    }

    @Override
    public void forEach(Consumer<? super Data> action) {
        value.forEach(action);
    }

    @Override
    public Spliterator<Data> spliterator() {
        return value.spliterator();
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof ListData(
                List<Data> value1
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
