package com.gto.datasynclib.datasream.codec;

import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.datasream.data.DataOps;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

public interface ByteStreamCodec<T> extends ByteStreamDecoder<T>, ByteStreamEncoder<T> {

    static <T> ByteStreamCodec<T> of(ByteStreamEncoder<? super T> encoder, ByteStreamDecoder<? extends T> decoder) {
        return new ByteStreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                encoder.encode(buf, obj);
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return decoder.decode(buf);
            }
        };
    }

    static <T> ByteStreamCodec<T> of(DataCodec<T> codec) {
        return new ByteStreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                Data.writeData(buf, codec.encode(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return codec.decode(Data.readData(buf));
            }
        };
    }

    static <T> ByteStreamCodec<T> of(Codec<T> codec) {
        return new ByteStreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                Data.writeData(buf, codec.encodeStart(DataOps.INSTANCE, obj).result().orElseThrow());
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return codec.decode(DataOps.INSTANCE, Data.readData(buf)).result().orElseThrow().getFirst();
            }
        };
    }

    static <T> void registerCodec(Class<T> type, ByteStreamCodec<T> codec) {
        synchronized (Codecs.CODECS) {
            Codecs.CODECS.put(type, codec);
        }
    }

    static <T> ByteStreamCodec<T> getCodec(Class<T> type) {
        return (ByteStreamCodec<T>) Codecs.CODECS.get(type);
    }

    ByteStreamCodec<Boolean> BOOLEAN_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Boolean obj) {
            buf.writeBoolean(obj);
        }

        @Override
        public Boolean decode(FriendlyByteBuf buf) {
            return buf.readBoolean();
        }

        static {
            registerCodec(Boolean.class, BOOLEAN_CODEC);
            registerCodec(boolean.class, BOOLEAN_CODEC);
        }
    };

    ByteStreamCodec<Byte> BYTE_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Byte obj) {
            buf.writeByte(obj);
        }

        @Override
        public Byte decode(FriendlyByteBuf buf) {
            return buf.readByte();
        }

        static {
            registerCodec(Byte.class, BYTE_CODEC);
            registerCodec(byte.class, BYTE_CODEC);
        }
    };

    ByteStreamCodec<Short> SHORT_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Short obj) {
            buf.writeShort(obj);
        }

        @Override
        public Short decode(FriendlyByteBuf buf) {
            return buf.readShort();
        }

        static {
            registerCodec(Short.class, SHORT_CODEC);
            registerCodec(short.class, SHORT_CODEC);
        }
    };

    ByteStreamCodec<Character> CHAR_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Character obj) {
            buf.writeChar(obj);
        }

        @Override
        public Character decode(FriendlyByteBuf buf) {
            return buf.readChar();
        }

        static {
            registerCodec(Character.class, CHAR_CODEC);
            registerCodec(char.class, CHAR_CODEC);
        }
    };

    ByteStreamCodec<Integer> INT_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Integer obj) {
            buf.writeInt(obj);
        }

        @Override
        public Integer decode(FriendlyByteBuf buf) {
            return buf.readInt();
        }

        static {
            registerCodec(Integer.class, INT_CODEC);
            registerCodec(int.class, INT_CODEC);
        }
    };

    ByteStreamCodec<Long> LONG_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Long obj) {
            buf.writeLong(obj);
        }

        @Override
        public Long decode(FriendlyByteBuf buf) {
            return buf.readLong();
        }

        static {
            registerCodec(Long.class, LONG_CODEC);
            registerCodec(long.class, LONG_CODEC);
        }
    };

    ByteStreamCodec<Float> FLOAT_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Float obj) {
            buf.writeFloat(obj);
        }

        @Override
        public Float decode(FriendlyByteBuf buf) {
            return buf.readFloat();
        }

        static {
            registerCodec(Float.class, FLOAT_CODEC);
            registerCodec(float.class, FLOAT_CODEC);
        }
    };

    ByteStreamCodec<Double> DOUBLE_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Double obj) {
            buf.writeDouble(obj);
        }

        @Override
        public Double decode(FriendlyByteBuf buf) {
            return buf.readDouble();
        }

        static {
            registerCodec(Double.class, DOUBLE_CODEC);
            registerCodec(double.class, DOUBLE_CODEC);
        }
    };

    ByteStreamCodec<String> STRING_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, String obj) {
            buf.writeUtf(obj);
        }

        @Override
        public String decode(FriendlyByteBuf buf) {
            return buf.readUtf();
        }

        static {
            registerCodec(String.class, STRING_CODEC);
        }
    };

    ByteStreamCodec<BigInteger> BIG_INTEGER_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, BigInteger obj) {
            buf.writeByteArray(obj.toByteArray());
        }

        @Override
        public BigInteger decode(FriendlyByteBuf buf) {
            return new BigInteger(buf.readByteArray());
        }

        static {
            registerCodec(BigInteger.class, BIG_INTEGER_CODEC);
        }
    };

    ByteStreamCodec<boolean[]> BOOLEANS_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, boolean[] obj) {
            buf.writeVarInt(obj.length);
            for (var b : obj) {
                buf.writeBoolean(b);
            }
        }

        @Override
        public boolean[] decode(FriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var booleans = new boolean[length];
            for (int i = 0; i < length; i++) {
                booleans[i] = buf.readBoolean();
            }
            return booleans;
        }

        static {
            registerCodec(boolean[].class, BOOLEANS_CODEC);
        }
    };

    ByteStreamCodec<byte[]> BYTES_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, byte[] obj) {
            buf.writeByteArray(obj);
        }

        @Override
        public byte[] decode(FriendlyByteBuf buf) {
            return buf.readByteArray();
        }

        static {
            registerCodec(byte[].class, BYTES_CODEC);
        }
    };

    ByteStreamCodec<int[]> INTS_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, int[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeInt(i);
            }
        }

        @Override
        public int[] decode(FriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var ints = new int[length];
            for (int i = 0; i < length; i++) {
                ints[i] = buf.readInt();
            }
            return ints;
        }

        static {
            registerCodec(int[].class, INTS_CODEC);
        }
    };

    ByteStreamCodec<long[]> LONGS_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, long[] obj) {
            buf.writeLongArray(obj);
        }

        @Override
        public long[] decode(FriendlyByteBuf buf) {
            return buf.readLongArray();
        }

        static {
            registerCodec(long[].class, LONGS_CODEC);
        }
    };

    ByteStreamCodec<UUID> UUID_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, UUID obj) {
            buf.writeUUID(obj);
        }

        @Override
        public UUID decode(FriendlyByteBuf buf) {
            return buf.readUUID();
        }

        static {
            registerCodec(UUID.class, UUID_CODEC);
        }
    };

    final class Codecs {

        private static final Map<Class<?>, ByteStreamCodec<?>> CODECS = new Reference2ReferenceOpenHashMap<>();
    }
}
