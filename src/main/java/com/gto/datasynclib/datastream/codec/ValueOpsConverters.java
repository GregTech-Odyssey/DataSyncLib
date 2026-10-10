package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.util.VarInts;
import com.gto.datasynclib.datastream.codec.ValueOps.Type;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.SortedSet;
import java.util.UUID;

@UtilityClass
public class ValueOpsConverters {

    public final ValueConverters.Converter<boolean[]> BOOLEAN_ARRAY = ValueConverters.Converter
            .builder(Type.BYTE_ARRAY, boolean[].class)
            .write((ops, value) -> {
                var bytes = new byte[value.length];
                for (int i = 0; i < value.length; i++) {
                    bytes[i] = value[i] ? (byte) 1 : (byte) 0;
                }
                return ops.createByteArray(bytes);
            })
            .read((ops, data) -> {
                var bytes = ops.getByteArray(data);
                var value = new boolean[bytes.length];
                for (int i = 0; i < bytes.length; i++) {
                    value[i] = bytes[i] == 1;
                }
                return value;
            })
            .build();

    public final ValueConverters.Converter<short[]> SHORT_ARRAY = ValueConverters.Converter
            .builder(Type.INT_ARRAY, short[].class)
            .write((ops, value) -> {
                var ints = new int[value.length];
                for (int i = 0; i < value.length; i++) {
                    ints[i] = value[i];
                }
                return ops.createIntArray(ints);
            })
            .read((ops, data) -> {
                var ints = ops.getIntArray(data);
                var value = new short[ints.length];
                for (int i = 0; i < ints.length; i++) {
                    value[i] = (short) ints[i];
                }
                return value;
            })
            .build();

    public final ValueConverters.Converter<char[]> CHAR_ARRAY = ValueConverters.Converter
            .builder(Type.INT_ARRAY, char[].class)
            .write((ops, value) -> {
                var ints = new int[value.length];
                for (int i = 0; i < value.length; i++) {
                    ints[i] = value[i];
                }
                return ops.createIntArray(ints);
            })
            .read((ops, data) -> {
                var ints = ops.getIntArray(data);
                var value = new char[ints.length];
                for (int i = 0; i < ints.length; i++) {
                    value[i] = (char) ints[i];
                }
                return value;
            })
            .build();

    public final ValueConverters.Converter<float[]> FLOAT_ARRAY = ValueConverters.Converter
            .builder(Type.INT_ARRAY, float[].class)
            .write((ops, value) -> {
                var ints = new int[value.length];
                for (int i = 0; i < value.length; i++) {
                    ints[i] = Float.floatToIntBits(value[i]);
                }
                return ops.createIntArray(ints);
            })
            .read((ops, data) -> {
                var ints = ops.getIntArray(data);
                var value = new float[ints.length];
                for (int i = 0; i < ints.length; i++) {
                    value[i] = Float.intBitsToFloat(ints[i]);
                }
                return value;
            })
            .build();

    public final ValueConverters.Converter<double[]> DOUBLE_ARRAY = ValueConverters.Converter
            .builder(Type.LONG_ARRAY, double[].class)
            .write((ops, value) -> {
                var longs = new long[value.length];
                for (int i = 0; i < value.length; i++) {
                    longs[i] = Double.doubleToLongBits(value[i]);
                }
                return ops.createLongArray(longs);
            })
            .read((ops, data) -> {
                var longs = ops.getLongArray(data);
                var value = new double[longs.length];
                for (int i = 0; i < longs.length; i++) {
                    value[i] = Double.longBitsToDouble(longs[i]);
                }
                return value;
            })
            .build();

    /**
     * A {@code List<Integer>} of FastUtil's own as an {@code int[]} — the same payload the primitive
     * array codec writes, so a set of ids costs four bytes each and nothing per element.
     */
    public final ValueConverters.Converter<IntList> INT_LIST = ValueConverters.Converter
            .builder(Type.INT_ARRAY, IntList.class)
            .when(IntList.class::isAssignableFrom)
            .write((ops, value) -> ops.createIntArray(value.toIntArray()))
            .read((ops, data) -> new IntArrayList(ops.getIntArray(data)))
            .build();

    /**
     * A {@code List<Long>} of FastUtil's own as a {@code long[]}.
     */
    public final ValueConverters.Converter<LongList> LONG_LIST = ValueConverters.Converter
            .builder(Type.LONG_ARRAY, LongList.class)
            .when(LongList.class::isAssignableFrom)
            .write((ops, value) -> ops.createLongArray(value.toLongArray()))
            .read((ops, data) -> new LongArrayList(ops.getLongArray(data)))
            .build();

    /**
     * A FastUtil {@code Set<Integer>} as an {@code int[]}, read back into the same
     * {@link IntOpenHashSet} the field codec builds — a payload of elements holds no order or kind of its
     * own, and a <em>sorted</em> set is left out on purpose: rebuilding one would need a comparator the
     * payload does not carry, so a sorted set keeps Java serialization rather than being handed a set of
     * another kind.
     */
    public final ValueConverters.Converter<IntSet> INT_SET = ValueConverters.Converter
            .builder(Type.INT_ARRAY, IntSet.class)
            .when(type -> IntSet.class.isAssignableFrom(type) && !SortedSet.class.isAssignableFrom(type))
            .write((ops, value) -> ops.createIntArray(value.toIntArray()))
            .read((ops, data) -> new IntOpenHashSet(ops.getIntArray(data)))
            .build();

    /**
     * A FastUtil {@code Set<Long>} as a {@code long[]}, on the terms {@link #INT_SET} describes.
     */
    public final ValueConverters.Converter<LongSet> LONG_SET = ValueConverters.Converter
            .builder(Type.LONG_ARRAY, LongSet.class)
            .when(type -> LongSet.class.isAssignableFrom(type) && !SortedSet.class.isAssignableFrom(type))
            .write((ops, value) -> ops.createLongArray(value.toLongArray()))
            .read((ops, data) -> new LongOpenHashSet(ops.getLongArray(data)))
            .build();


    public final ValueConverters.Converter<UUID> UUID = ValueConverters.Converter
            .builder(Type.LONG_ARRAY, UUID.class)
            .write((ops, value) -> ops.createLongArray(new long[]{
                    value.getMostSignificantBits(), value.getLeastSignificantBits()}))
            .read((ops, data) -> {
                var longs = ops.getLongArray(data);
                return new UUID(longs[0], longs[1]);
            })
            .build();

    public final ValueConverters.Converter<BigInteger> BIG_INTEGER = ValueConverters.Converter
            .builder(Type.BYTE_ARRAY, BigInteger.class)
            .write((ops, value) -> ops.createByteArray(value.toByteArray()))
            .read((ops, data) -> {
                var bytes = ops.getByteArray(data);
                return bytes.length == 0 ? BigInteger.ZERO : new BigInteger(bytes);
            })
            .build();

    public final ValueConverters.Converter<BigDecimal> BIG_DECIMAL = ValueConverters.Converter
            .builder(Type.BYTE_ARRAY, BigDecimal.class)
            .write((ops, value) -> ops.createByteArray(bigDecimalPayload(value)))
            .read((ops, data) -> readBigDecimal(ops.getByteArray(data)))
            .build();

    /**
     * A stored {@link BigDecimal}: its scale as a VarInt and then the two's-complement bytes of its unscaled
     * value. The scale leads because reading it back needs no length to skip first, and it is a VarInt rather
     * than the fixed width it used to be — a number written in JSON can carry any exponent, so {@code 1e400}
     * has scale -400 and {@code 1e-400} scale 400, and neither fits the one byte that used to be there.
     */
    private byte[] bigDecimalPayload(BigDecimal value) {
        var unscaled = value.unscaledValue().toByteArray();
        var scale = value.scale();
        var width = varIntWidth(scale);
        var payload = new byte[width + unscaled.length];
        System.arraycopy(unscaled, 0, payload, width, unscaled.length);
        for (int i = 0; i < width - 1; i++) {
            payload[i] = (byte) ((scale & 0x7F) | 0x80);
            scale >>>= 7;
        }
        payload[width - 1] = (byte) scale;
        return payload;
    }

    /**
     * Reads a {@link #bigDecimalPayload(BigDecimal)} payload back.
     */
    private BigDecimal readBigDecimal(byte[] payload) {
        var scale = 0;
        var shift = 0;
        var index = 0;
        while (true) {
            var b = payload[index++];
            scale |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift >= 32) throw new IllegalArgumentException("A stored BigDecimal has an over-long scale");
        }
        var unscaled = new byte[payload.length - index];
        System.arraycopy(payload, index, unscaled, 0, unscaled.length);
        return new BigDecimal(new BigInteger(unscaled), scale);
    }

    /**
     * How many bytes {@link #bigDecimalPayload(BigDecimal)} gives a scale — the width of the VarInt
     * {@link VarInts#write(io.netty.buffer.ByteBuf, int)} would write, so a payload can be sized once.
     */
    private int varIntWidth(int value) {
        var width = 1;
        while ((value & ~0x7F) != 0) {
            width++;
            value >>>= 7;
        }
        return width;
    }


    public void init() {
    }

}
