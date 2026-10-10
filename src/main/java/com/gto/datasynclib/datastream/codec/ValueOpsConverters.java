package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.datastream.codec.ValueOps.Type;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
            .write((ops, value) -> {
                var unscaled = value.unscaledValue().toByteArray();
                var scale = value.scale();
                if (scale < 0 || scale > 255) {
                    throw new IllegalArgumentException("A BigDecimal scale of " + scale
                            + " does not fit the one-byte scale a stored BigDecimal has");
                }
                var payload = new byte[unscaled.length + 1];
                System.arraycopy(unscaled, 0, payload, 0, unscaled.length);
                payload[unscaled.length] = (byte) scale;
                return ops.createByteArray(payload);
            })
            .read((ops, data) -> {
                var bytes = ops.getByteArray(data);
                if (bytes.length == 0) return BigDecimal.ZERO;
                var unscaled = new byte[bytes.length - 1];
                System.arraycopy(bytes, 0, unscaled, 0, unscaled.length);
                return new BigDecimal(new BigInteger(unscaled), bytes[bytes.length - 1] & 0xFF);
            })
            .build();


    public void init() {
    }

}
