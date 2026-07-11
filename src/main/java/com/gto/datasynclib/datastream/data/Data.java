package com.gto.datasynclib.datastream.data;

import com.gto.datasynclib.datastream.codec.ByteStreamCodec;
import com.gto.datasynclib.datastream.codec.DataCodec;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central sealed interface for the DataSyncLib type system — a lightweight, binary-format data representation
 * similar in concept to Minecraft's NBT but designed specifically for field synchronization and persistence.
 *
 * <h3>Type Hierarchy</h3>
 * <ul>
 *   <li>{@link ImmutableData} — permits {@link NullData}, {@link NumericData}, {@link CharData}, {@link StringData}</li>
 *   <li>{@link CollectionData} — permits {@link MapData}, {@link ListData}, {@link ByteArrayData}, {@link IntArrayData}, {@link LongArrayData}</li>
 *   <li>{@link CustomData} — extensible custom type with user-registered serialization</li>
 * </ul>
 *
 * <h3>Wire Format Type IDs</h3>
 * Each Data subtype has a unique type-ID byte used as the first byte in binary serialization:
 * <table>
 *   <tr><th>ID</th><th>Constant</th><th>Data Subtype</th><th>Java Type</th></tr>
 *   <tr><td>0</td><td>NULL</td><td>NullData</td><td>null</td></tr>
 *   <tr><td>1</td><td>BOOLEAN</td><td>ByteData</td><td>boolean</td></tr>
 *   <tr><td>2</td><td>BYTE</td><td>ByteData</td><td>byte</td></tr>
 *   <tr><td>3</td><td>SHORT</td><td>ShortData</td><td>short</td></tr>
 *   <tr><td>4</td><td>CHAR</td><td>CharData</td><td>char</td></tr>
 *   <tr><td>5</td><td>INT</td><td>IntData</td><td>int</td></tr>
 *   <tr><td>6</td><td>LONG</td><td>LongData</td><td>long</td></tr>
 *   <tr><td>7</td><td>FLOAT</td><td>FloatData</td><td>float</td></tr>
 *   <tr><td>8</td><td>DOUBLE</td><td>DoubleData</td><td>double</td></tr>
 *   <tr><td>9</td><td>BYTE_ARRAY</td><td>ByteArrayData</td><td>byte[]</td></tr>
 *   <tr><td>10</td><td>INT_ARRAY</td><td>IntArrayData</td><td>int[]</td></tr>
 *   <tr><td>11</td><td>LONG_ARRAY</td><td>LongArrayData</td><td>long[]</td></tr>
 *   <tr><td>12</td><td>STRING</td><td>StringData</td><td>String</td></tr>
 *   <tr><td>13</td><td>LIST</td><td>ListData</td><td>List&lt;Data&gt;</td></tr>
 *   <tr><td>14</td><td>STRING_MAP</td><td>StringMapData</td><td>Map&lt;String, Data&gt;</td></tr>
 *   <tr><td>15</td><td>CUSTOM</td><td>CustomData</td><td>(user-defined)</td></tr>
 *   <tr><td>16</td><td>DATA_MAP</td><td>DataMapData</td><td>Map&lt;Data, Data&gt;</td></tr>
 *   <tr><td>17</td><td>INT_MAP</td><td>IntMapData</td><td>Int2ObjectMap&lt;Data&gt;</td></tr>
 *   <tr><td>18</td><td>LONG_MAP</td><td>LongMapData</td><td>Long2ObjectMap&lt;Data&gt;</td></tr>
 * </table>
 *
 * <h3>Serialization Paths</h3>
 * <ul>
 *   <li>{@link #BYTE_STREAM_CODEC} — network serialization via {@link FriendlyByteBuf} (VarInt length-prefixed)</li>
 *   <li>{@link #DATA_CODEC} — persistence serialization via Data objects (identity pass-through)</li>
 *   <li>{@link #CODEC} — Mojang DFU {@link com.mojang.serialization.Codec} integration via {@link DynamicOps}</li>
 * </ul>
 *
 * <h3>VarInt/VarLong Encoding</h3>
 * Uses a variable-length 7-bit-per-byte encoding (compatible with Minecraft's protocol):
 * the high bit of each byte indicates whether more bytes follow.
 *
 * <h3>Safe vs Unchecked Cast Methods</h3>
 * {@code toXxxData()} methods return {@code null} if the instance is not the expected type.
 * {@code asXxxData()} methods perform a direct cast and will throw {@code ClassCastException} on mismatch.
 *
 * @see NullData
 * @see ByteArrayData
 * @see ListData
 * @see StringMapData
 */
public sealed interface Data permits CollectionData, ImmutableData, CustomData {

    Codec<Data> CODEC = new Codec<>() {

        @Override
        public <T> DataResult<Pair<Data, T>> decode(DynamicOps<T> ops, T input) {
            return DataResult.success(Pair.of(Data.readData(Unpooled.copiedBuffer(ops.getByteBuffer(input).result().orElseThrow())), ops.empty()));
        }

        @Override
        public <T> DataResult<T> encode(Data input, DynamicOps<T> ops, T prefix) {
            return DataResult.success(ops.createByteList(ByteBuffer.wrap(input.writeToBytes())));
        }
    };

    ByteStreamCodec<Data> BYTE_STREAM_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, Data obj) {
            Data.writeData(stream, obj);
        }

        @Override
        public Data decode(FriendlyByteBuf stream) {
            return Data.readData(stream);
        }

        static {
            ByteStreamCodec.registerCodec(Data.class, BYTE_STREAM_CODEC);
        }
    };

    DataCodec<Data> DATA_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Data obj) {
            return obj;
        }

        @Override
        public Data decode(@NotNull Data data, int dataVersion) {
            return data;
        }

        static {
            DataCodec.registerCodec(Data.class, DATA_CODEC);
        }
    };

    // ===== Type ID constants for wire format =====

    byte NULL = 0;
    byte BOOLEAN = 1;
    byte BYTE = 2;
    byte SHORT = 3;
    byte CHAR = 4;
    byte INT = 5;
    byte LONG = 6;
    byte FLOAT = 7;
    byte DOUBLE = 8;
    byte BYTE_ARRAY = 9;
    byte INT_ARRAY = 10;
    byte LONG_ARRAY = 11;
    byte STRING = 12;
    byte LIST = 13;
    byte STRING_MAP = 14;
    byte CUSTOM = 15;
    byte DATA_MAP = 16;
    byte INT_MAP = 17;
    byte LONG_MAP = 18;

    // ===== Binary serialization (ByteBuf) =====

    static Data readData(byte id, ByteBuf stream) {
        return switch (id) {
            case NULL -> NullData.INSTANCE;
            case BOOLEAN -> ByteData.valueOf(stream.readBoolean());
            case BYTE -> ByteData.valueOf(stream.readByte());
            case SHORT -> ShortData.valueOf(stream.readShort());
            case CHAR -> CharData.valueOf(stream.readChar());
            case INT -> IntData.valueOf(stream.readInt());
            case LONG -> LongData.valueOf(stream.readLong());
            case FLOAT -> FloatData.valueOf(stream.readFloat());
            case DOUBLE -> DoubleData.valueOf(stream.readDouble());
            case STRING -> StringData.valueOf(readString(stream));
            case BYTE_ARRAY -> new ByteArrayData(readByteArray(stream));
            case INT_ARRAY -> new IntArrayData(readIntArray(stream));
            case LONG_ARRAY -> new LongArrayData(readLongArray(stream));
            case LIST -> ListData.read(stream);
            case STRING_MAP -> StringMapData.read(stream);
            case CUSTOM -> CustomData.read(readVarInt(stream), stream);
            case DATA_MAP -> DataMapData.read(stream);
            case INT_MAP -> IntMapData.read(stream);
            case LONG_MAP -> LongMapData.read(stream);
            default -> throw new IllegalArgumentException("Unknown data type id: " + id);
        };
    }

    static Data readData(byte[] bytes) {
        var buf = Unpooled.wrappedBuffer(bytes);
        try {
            return readData(buf.readByte(), buf);
        } finally {
            buf.release();
        }
    }

    default byte[] writeToBytes() {
        var buf = Unpooled.buffer();
        try {
            writeData(buf, this);
            buf.readerIndex(0);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
    }

    static Data readData(ByteBuf buf) {
        return readData(buf.readByte(), buf);
    }

    // ===== VarInt/VarLong encoding (Minecraft protocol compatible) =====

    static void writeData(ByteBuf buf, Data data) {
        buf.writeByte(data.getId());
        data.write(buf);
    }

    static void writeVarInt(ByteBuf buf, int input) {
        while ((input & -128) != 0) {
            buf.writeByte(input & 127 | 128);
            input >>>= 7;
        }
        buf.writeByte(input);
    }

    static int readVarInt(ByteBuf buf) {
        int i = 0;
        int j = 0;
        byte b0;
        do {
            b0 = buf.readByte();
            i |= (b0 & 127) << j++ * 7;
            if (j > 5) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((b0 & 128) == 128);
        return i;
    }

    static void writeVarLong(ByteBuf buf, long input) {
        while ((input & -128L) != 0L) {
            buf.writeByte((int) (input & 127L) | 128);
            input >>>= 7;
        }

        buf.writeByte((int) input);
    }

    static long readVarLong(ByteBuf buf) {
        long i = 0L;
        int j = 0;

        byte b0;
        do {
            b0 = buf.readByte();
            i |= (long) (b0 & 127) << j++ * 7;
            if (j > 10) {
                throw new RuntimeException("VarLong too big");
            }
        } while ((b0 & 128) == 128);

        return i;
    }

    static void writeString(ByteBuf buf, String s) {
        writeByteArray(buf, s.getBytes(StandardCharsets.UTF_8));
    }

    static String readString(ByteBuf buf) {
        return new String(readByteArray(buf), StandardCharsets.UTF_8);
    }

    static void writeByteArray(ByteBuf buf, byte[] array) {
        writeVarInt(buf, array.length);
        buf.writeBytes(array);
    }

    static byte[] readByteArray(ByteBuf buf) {
        byte[] array = new byte[readVarInt(buf)];
        buf.readBytes(array);
        return array;
    }

    static void writeIntArray(ByteBuf buf, int[] array) {
        writeVarInt(buf, array.length);
        for (int i : array) {
            buf.writeInt(i);
        }
    }

    static int[] readIntArray(ByteBuf buf) {
        int i = readVarInt(buf);
        int[] aint = new int[i];
        for (int j = 0; j < i; ++j) {
            aint[j] = buf.readInt();
        }
        return aint;
    }

    static void writeLongArray(ByteBuf buf, long[] array) {
        writeVarInt(buf, array.length);
        for (long i : array) {
            buf.writeLong(i);
        }
    }

    static long[] readLongArray(ByteBuf buf) {
        int i = readVarInt(buf);
        var array = new long[i];
        for (int j = 0; j < i; ++j) {
            array[j] = buf.readLong();
        }
        return array;
    }

    void write(ByteBuf stream);

    byte getId();

    Data copy();

    // ===== Default getter methods (safe defaults for all types) =====

    default boolean isNull() {
        return false;
    }

    default boolean getBoolean() {
        return false;
    }

    default byte getByte() {
        return 0;
    }

    default short getShort() {
        return 0;
    }

    default char getChar() {
        return 0;
    }

    default int getInt() {
        return 0;
    }

    default long getLong() {
        return 0;
    }

    default float getFloat() {
        return 0;
    }

    default double getDouble() {
        return 0;
    }

    default byte @NotNull [] getByteArray() {
        return ArrayUtils.EMPTY_BYTE_ARRAY;
    }

    default int @NotNull [] getIntArray() {
        return ArrayUtils.EMPTY_INT_ARRAY;
    }

    default long @NotNull [] getLongArray() {
        return ArrayUtils.EMPTY_LONG_ARRAY;
    }

    @NotNull
    default String getString() {
        return "";
    }

    @NotNull
    default List<Data> getList() {
        return Collections.emptyList();
    }

    @NotNull
    default Map<Data, Data> getDataMap() {
        return Collections.emptyMap();
    }

    @NotNull
    default Map<String, Data> getStringMap() {
        return Collections.emptyMap();
    }

    // ===== Static factory methods =====

    static ByteData valueOf(boolean value) {
        return ByteData.valueOf(value);
    }

    static ByteData valueOf(byte value) {
        return ByteData.valueOf(value);
    }

    static ShortData valueOf(short value) {
        return ShortData.valueOf(value);
    }

    static CharData valueOf(char value) {
        return CharData.valueOf(value);
    }

    static IntData valueOf(int value) {
        return IntData.valueOf(value);
    }

    static LongData valueOf(long value) {
        return LongData.valueOf(value);
    }

    static FloatData valueOf(float value) {
        return FloatData.valueOf(value);
    }

    static DoubleData valueOf(double value) {
        return DoubleData.valueOf(value);
    }

    static StringData valueOf(String value) {
        return StringData.valueOf(value);
    }

    static ByteArrayData valueOf(byte[] value) {
        return ByteArrayData.valueOf(value);
    }

    static IntArrayData valueOf(int[] value) {
        return IntArrayData.valueOf(value);
    }

    static LongArrayData valueOf(long[] value) {
        return LongArrayData.valueOf(value);
    }

    static ByteArrayData valueOf(boolean[] value) {
        var length = value.length;
        var arr = new byte[length];
        for (int i = 0; i < length; i++) {
            arr[i] = (byte) (value[i] ? 1 : 0);
        }
        return ByteArrayData.valueOf(arr);
    }

    static IntArrayData valueOf(short[] value) {
        int length = value.length;
        int[] arr = new int[length];
        for (int i = 0; i < length; i++) {
            arr[i] = value[i];
        }
        return IntArrayData.valueOf(arr);
    }

    static IntArrayData valueOf(char[] value) {
        int length = value.length;
        int[] arr = new int[length];
        for (int i = 0; i < length; i++) {
            arr[i] = value[i];
        }
        return IntArrayData.valueOf(arr);
    }

    static IntArrayData valueOf(float[] value) {
        var length = value.length;
        int[] arr = new int[length];
        for (int i = 0; i < length; i++) {
            arr[i] = Float.floatToIntBits(value[i]);
        }
        return IntArrayData.valueOf(arr);
    }

    static LongArrayData valueOf(double[] value) {
        var length = value.length;
        long[] arr = new long[length];
        for (int i = 0; i < length; i++) {
            arr[i] = Double.doubleToLongBits(value[i]);
        }
        return LongArrayData.valueOf(arr);
    }

    static LongArrayData valueOf(UUID uuid) {
        return new LongArrayData(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    static ByteArrayData valueOf(BigInteger bigInteger) {
        return new ByteArrayData(bigInteger.toByteArray());
    }

    // ===== Array and complex type converters =====

    default boolean[] getBooleanArray() {
        var array = getByteArray();
        var length = array.length;
        var result = new boolean[length];
        for (int i = 0; i < length; i++) {
            result[i] = array[i] == 1;
        }
        return result;
    }

    default short[] getShortArray() {
        var array = getIntArray();
        var length = array.length;
        var result = new short[length];
        for (int i = 0; i < length; i++) {
            result[i] = (short) array[i];
        }
        return result;
    }

    default char[] getCharArray() {
        var array = getIntArray();
        var length = array.length;
        var result = new char[length];
        for (int i = 0; i < length; i++) {
            result[i] = (char) array[i];
        }
        return result;
    }

    default float[] getFloatArray() {
        var array = getIntArray();
        var length = array.length;
        var result = new float[length];
        for (int i = 0; i < length; i++) {
            result[i] = Float.intBitsToFloat(array[i]);
        }
        return result;
    }

    default double[] getDoubleArray() {
        var array = getLongArray();
        var length = array.length;
        var result = new double[length];
        for (int i = 0; i < length; i++) {
            result[i] = Double.longBitsToDouble(array[i]);
        }
        return result;
    }

    default UUID getUUID() {
        var array = getLongArray();
        if (array == ArrayUtils.EMPTY_LONG_ARRAY) return null;
        return new UUID(array[0], array[1]);
    }

    default BigInteger getBigInteger() {
        var array = getByteArray();
        if (array == ArrayUtils.EMPTY_BYTE_ARRAY) return BigInteger.ZERO;
        return new BigInteger(array);
    }

    @Nullable
    // ===== Safe and unchecked cast methods =====

    default ListData toListData() {
        return this instanceof ListData listData ? listData : null;
    }

    @Nullable
    default DataMapData toDataMapData() {
        return this instanceof DataMapData dataMapData ? dataMapData : null;
    }

    @Nullable
    default StringMapData toStringMapData() {
        return this instanceof StringMapData stringMapData ? stringMapData : null;
    }

    @Nullable
    default IntMapData toIntMapData() {
        return this instanceof IntMapData intMapData ? intMapData : null;
    }

    @Nullable
    default LongMapData toLongMapData() {
        return this instanceof LongMapData longMapData ? longMapData : null;
    }

    @Nullable
    default <T> CustomData<T> toCustomData(CustomData.Type<T> type) {
        return null;
    }

    default ListData asListData() {
        return (ListData) this;
    }

    default DataMapData asDataMapData() {
        return (DataMapData) this;
    }

    default StringMapData asStringMapData() {
        return (StringMapData) this;
    }

    default IntMapData asIntMapData() {
        return (IntMapData) this;
    }

    default LongMapData asLongMapData() {
        return (LongMapData) this;
    }

    default <T> CustomData<T> asCustomData() {
        return (CustomData<T>) this;
    }
}
