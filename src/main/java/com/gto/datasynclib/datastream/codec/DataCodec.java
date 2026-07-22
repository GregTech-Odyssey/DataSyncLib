package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.datastream.data.*;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Combined encoder/decoder interface for {@link com.gto.datasynclib.datastream.data.Data}-based
 * persistent storage.
 *
 * <p>Extends both {@link DataDecoder} and {@link DataEncoder} for bidirectional serialization.
 * Contains built-in codec constants for all Java primitives, arrays, String, UUID, and BigInteger —
 * each auto-registering in the global {@link Codecs} registry.
 *
 * <p>Factory methods {@link #of} adapt from {@link ByteStreamCodec}, Mojang {@link com.mojang.serialization.Codec},
 * or custom encoder/decoder pairs. The {@link #map} method creates adapted codecs with converter functions.
 *
 * @param <T> the type this codec can encode and decode
 */
public interface DataCodec<T> extends DataEncoder<T>, DataDecoder<T> {

    default Codec<T> toCodec(int dataVersion) {
        var codec = this;
        return new Codec<>() {

            @Override
            public <T1> DataResult<T1> encode(T input, DynamicOps<T1> ops, T1 prefix) {
                return DataResult.success(ops.createByteList(ByteBuffer.wrap(codec.encode(input).writeToBytes())));
            }

            @Override
            public <T1> DataResult<Pair<T, T1>> decode(DynamicOps<T1> ops, T1 input) {
                return DataResult.success(Pair.of(codec.decode(Data.readData(Unpooled.copiedBuffer(ops.getByteBuffer(input).result().orElseThrow())), dataVersion), ops.empty()));
            }
        };
    }

    static <T> DataCodec<T> of(ByteStreamCodec<T> codec) {
        return new DataCodec<>() {

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var buf = Unpooled.wrappedBuffer(data.getByteArray());
                var wrapper = new FriendlyByteBuf(buf);
                try {
                    return codec.decode(wrapper);
                } finally {
                    buf.release();
                }
            }

            @Override
            public @NotNull Data encode(T obj) {
                var buf = Unpooled.buffer();
                var wrapper = new FriendlyByteBuf(buf);
                try {
                    codec.encode(wrapper, obj);
                    buf.readerIndex(0);
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new ByteArrayData(data);
                } finally {
                    buf.release();
                }
            }
        };
    }

    static <T> DataCodec<T> of(Codec<T> codec) {
        return new DataCodec<>() {

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                return codec.decode(DataOps.INSTANCE, data).result().orElseThrow().getFirst();
            }

            @Override
            public @NotNull Data encode(T obj) {
                return codec.encodeStart(DataOps.INSTANCE, obj).result().orElseThrow();
            }
        };
    }

    static <T> DataCodec<T> of(DataEncoder<? super T> encoder, DataDecoder<? extends T> decoder) {
        return new DataCodec<>() {

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                return decoder.decode(data, dataVersion);
            }

            @Override
            public @NotNull Data encode(T obj) {
                return encoder.encode(obj);
            }
        };
    }

    static <K, V> DataCodec<V> map(DataCodec<K> codec, Function<? super V, ? extends K> encodeConverter, Function<? super K, ? extends V> decodeConverter) {
        return new DataCodec<>() {

            @Override
            public V decode(@NotNull Data data, int dataVersion) {
                return decodeConverter.apply(codec.decode(data, dataVersion));
            }

            @Override
            public @NotNull Data encode(V obj) {
                return codec.encode(encodeConverter.apply(obj));
            }
        };
    }

    static <T> void registerCodec(Class<T> type, DataCodec<T> codec) {
        synchronized (Codecs.CODECS) {
            Codecs.CODECS.put(type, codec);
        }
    }

    static <T> DataCodec<T> getCodec(Class<T> type) {
        return (DataCodec<T>) Codecs.CODECS.get(type);
    }

    DataCodec<Boolean> BOOLEAN_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Boolean obj) {
            return ByteData.valueOf(obj);
        }

        @Override
        public Boolean decode(@NotNull Data data, int dataVersion) {
            return data.getBoolean();
        }

        static {
            registerCodec(Boolean.class, BOOLEAN_CODEC);
            registerCodec(boolean.class, BOOLEAN_CODEC);
        }
    };

    DataCodec<Byte> BYTE_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Byte obj) {
            return ByteData.valueOf(obj);
        }

        @Override
        public Byte decode(@NotNull Data data, int dataVersion) {
            return data.getByte();
        }

        static {
            registerCodec(Byte.class, BYTE_CODEC);
            registerCodec(byte.class, BYTE_CODEC);
        }
    };

    DataCodec<Short> SHORT_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Short obj) {
            return ShortData.valueOf(obj);
        }

        @Override
        public Short decode(@NotNull Data data, int dataVersion) {
            return data.getShort();
        }

        static {
            registerCodec(Short.class, SHORT_CODEC);
            registerCodec(short.class, SHORT_CODEC);
        }
    };

    DataCodec<Character> CHAR_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Character obj) {
            return CharData.valueOf(obj);
        }

        @Override
        public Character decode(@NotNull Data data, int dataVersion) {
            return data.getChar();
        }

        static {
            registerCodec(Character.class, CHAR_CODEC);
            registerCodec(char.class, CHAR_CODEC);
        }
    };

    DataCodec<Integer> INT_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Integer obj) {
            return IntData.valueOf(obj);
        }

        @Override
        public Integer decode(@NotNull Data data, int dataVersion) {
            return data.getInt();
        }

        static {
            registerCodec(Integer.class, INT_CODEC);
            registerCodec(int.class, INT_CODEC);
        }
    };

    DataCodec<Long> LONG_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Long obj) {
            return LongData.valueOf(obj);
        }

        @Override
        public Long decode(@NotNull Data data, int dataVersion) {
            return data.getLong();
        }

        static {
            registerCodec(Long.class, LONG_CODEC);
            registerCodec(long.class, LONG_CODEC);
        }
    };

    DataCodec<Float> FLOAT_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Float obj) {
            return FloatData.valueOf(obj);
        }

        @Override
        public Float decode(@NotNull Data data, int dataVersion) {
            return data.getFloat();
        }

        static {
            registerCodec(Float.class, FLOAT_CODEC);
            registerCodec(float.class, FLOAT_CODEC);
        }
    };

    DataCodec<Double> DOUBLE_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Double obj) {
            return DoubleData.valueOf(obj);
        }

        @Override
        public Double decode(@NotNull Data data, int dataVersion) {
            return data.getDouble();
        }

        static {
            registerCodec(Double.class, DOUBLE_CODEC);
            registerCodec(double.class, DOUBLE_CODEC);
        }
    };

    DataCodec<String> STRING_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(String obj) {
            return StringData.valueOf(obj);
        }

        @Override
        public String decode(@NotNull Data data, int dataVersion) {
            return data.getString();
        }

        static {
            registerCodec(String.class, STRING_CODEC);
        }
    };

    DataCodec<BigInteger> BIG_INTEGER_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(BigInteger obj) {
            return Data.valueOf(obj);
        }

        @Override
        public BigInteger decode(@NotNull Data data, int dataVersion) {
            return data.getBigInteger();
        }

        static {
            registerCodec(BigInteger.class, BIG_INTEGER_CODEC);
        }
    };

    DataCodec<boolean[]> BOOLEANS_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(boolean[] obj) {
            return Data.valueOf(obj);
        }

        @Override
        public boolean[] decode(@NotNull Data data, int dataVersion) {
            return data.getBooleanArray();
        }

        static {
            registerCodec(boolean[].class, BOOLEANS_CODEC);
        }
    };

    DataCodec<byte[]> BYTES_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(byte[] obj) {
            return ByteArrayData.valueOf(obj);
        }

        @Override
        public byte[] decode(@NotNull Data data, int dataVersion) {
            return data.getByteArray();
        }

        static {
            registerCodec(byte[].class, BYTES_CODEC);
        }
    };

    DataCodec<short[]> SHORTS_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(short[] obj) {
            return Data.valueOf(obj);
        }

        @Override
        public short[] decode(@NotNull Data data, int dataVersion) {
            return data.getShortArray();
        }

        static {
            registerCodec(short[].class, SHORTS_CODEC);
        }
    };

    DataCodec<char[]> CHARS_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(char[] obj) {
            return Data.valueOf(obj);
        }

        @Override
        public char[] decode(@NotNull Data data, int dataVersion) {
            return data.getCharArray();
        }

        static {
            registerCodec(char[].class, CHARS_CODEC);
        }
    };

    DataCodec<int[]> INTS_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(int[] obj) {
            return IntArrayData.valueOf(obj);
        }

        @Override
        public int[] decode(@NotNull Data data, int dataVersion) {
            return data.getIntArray();
        }

        static {
            registerCodec(int[].class, INTS_CODEC);
        }
    };

    DataCodec<long[]> LONGS_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(long[] obj) {
            return LongArrayData.valueOf(obj);
        }

        @Override
        public long[] decode(@NotNull Data data, int dataVersion) {
            return data.getLongArray();
        }

        static {
            registerCodec(long[].class, LONGS_CODEC);
        }
    };

    DataCodec<float[]> FLOATS_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(float[] obj) {
            return Data.valueOf(obj);
        }

        @Override
        public float[] decode(@NotNull Data data, int dataVersion) {
            return data.getFloatArray();
        }

        static {
            registerCodec(float[].class, FLOATS_CODEC);
        }
    };

    DataCodec<double[]> DOUBLES_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(double[] obj) {
            return Data.valueOf(obj);
        }

        @Override
        public double[] decode(@NotNull Data data, int dataVersion) {
            return data.getDoubleArray();
        }

        static {
            registerCodec(double[].class, DOUBLES_CODEC);
        }
    };

    DataCodec<UUID> UUID_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(UUID obj) {
            return Data.valueOf(obj);
        }

        @Override
        public UUID decode(@NotNull Data data, int dataVersion) {
            return data.getUUID();
        }

        static {
            registerCodec(UUID.class, UUID_CODEC);
        }
    };

    final class Codecs {

        private static final Map<Class<?>, DataCodec<?>> CODECS = new Reference2ReferenceOpenHashMap<>();
    }
}
