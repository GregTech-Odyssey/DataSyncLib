package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.util.ByteBufCodecExtends;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The <strong>concrete-buffer</strong> half of the codec toolkit: every built-in codec, plus the
 * builders and adapters that have to name a real buffer — the shape of 1.21's
 * {@code net.minecraft.network.codec.ByteBufCodecs}, whose member names this follows (official
 * Mojang mappings: {@code BOOL}, {@code VAR_INT}, {@code STRING_UTF8}, {@code byteArray(int)},
 * {@code stringUtf8(int)}, {@code readCount} / {@code writeCount}, …), so that a codec written
 * against one version's names reads the same on the other.
 *
 * <p>{@link StreamCodec} itself is buffer-agnostic — it composes codecs for a buffer it never
 * mentions, which is what keeps a codec written for one buffer usable wherever a broader one is
 * expected. Everything that cannot be written that way lives here instead:</p>
 * <ul>
 *   <li>the constants: the numeric family is {@code StreamCodec<ByteBuf, …>} — the least a buffer can
 *       be while still carrying a VarInt ({@link VarInts}) — while {@link #STRING_UTF8} and the
 *       Minecraft value types ({@link ByteBufCodecExtends}, the extension of this
 *       interface) need {@link FriendlyByteBuf}'s own API</li>
 *   <li>{@link #optional}, {@link #map}, {@link #collection}, {@link #list}, {@link #set},
 *       {@link #array} and {@link #either}, which write a size or a presence flag and therefore need
 *       a real buffer — the container builders whose factory can be filled in on its own
 *       ({@code collection(IntFunction)}, {@code list()}, {@code list(int)}, {@code set()},
 *       {@code array(IntFunction)}) and the three {@code optional} adapters also exist as
 *       {@link StreamCodec.CodecOperation}s, read fluently as
 *       {@code STRING_UTF8.apply(ByteBufCodecs.list())} or
 *       {@code STRING_UTF8.apply(ByteBufCodecs.optional())}</li>
 *   <li>{@link #fromCodec(Codec)} / {@link #fromValueCodec(int, ValueCodec)}, which allocate a buffer of
 *       their own to carry the other path's payload, and {@link #get(Class)}, the network-side
 *       registry lookup</li>
 * </ul>
 *
 * <h3>Sizes and limits</h3>
 * <p>Every container builder comes in two flavours: the plain one, which trusts the payload, and an
 * overload taking a {@code maxSize} / {@code maxLength}, which rejects a larger size on decode
 * ({@link DecoderException}) and a larger container on encode ({@link EncoderException}). On an
 * untrusted channel the limited form is the one to use — the size it rejects is exactly the
 * attacker-controlled allocation. The unlimited constants are written in terms of the limited
 * methods with {@link Integer#MAX_VALUE}, so both flavours share one wire format.</p>
 *
 * <p>Where a size <em>is</em> trusted, {@link #MAX_INITIAL_COLLECTION_SIZE} still caps the capacity a
 * container is pre-allocated with, so a large-but-legal payload does not allocate up front; the
 * container grows as it is filled.</p>
 *
 * <h3>Naming</h3>
 * <p>The 1.21 names are used as-is: {@code INT} is the fixed four-byte integer while {@code VAR_INT}
 * is the variable-length one, {@code LONG} / {@code VAR_LONG} differ the same way, and the array
 * constants say which element format they carry ({@code INT_ARRAY} is fixed, {@code VAR_INT_ARRAY}
 * is VarInt, and so on). The FastUtil primitive collections — which vanilla has no counterpart for —
 * keep this library's own names ({@link #INT_LIST}, {@link #INT_SET}, {@link #LONG_LIST},
 * {@link #LONG_SET}).</p>
 *
 * <p>One deliberate difference from vanilla: {@link #optional} is <em>null-based</em> here (a
 * {@code false} flag on the encode side and a {@code null} on the decode side) rather than
 * {@code Optional}-based, because the whole library — {@link ValueCodec#optional()},
 * {@link StreamCodec} consumers and the annotation layer — treats {@code null} as the absent value.
 * The wire format is the same boolean flag vanilla writes.</p>
 */
@SuppressWarnings("unused")
public interface ByteBufCodecs {

    /**
     * Cap on the capacity a decoded container is pre-allocated with. The size itself is whatever the
     * payload says (or what the {@code maxSize} overload allows); only the up-front allocation is
     * clamped, so a legal but huge container fills up instead of allocating in one go.
     */
    int MAX_INITIAL_COLLECTION_SIZE = 65536;

    // ===== Collection sizes =====

    /**
     * Reads a container size written by {@link #writeCount(ByteBuf, int, int)}, rejecting anything
     * above {@code maxSize}.
     *
     * @throws DecoderException if the payload declares more than {@code maxSize} elements
     */
    static int readCount(ByteBuf buf, int maxSize) {
        var size = VarInts.read(buf);
        if (size > maxSize) {
            throw new DecoderException("Container size " + size + " is larger than the maximum allowed " + maxSize);
        }
        return size;
    }

    /**
     * Writes a container size as a VarInt, rejecting a container that does not fit {@code maxSize}
     * — the encode-side half of {@link #readCount(ByteBuf, int)}, so an oversized container fails
     * where it is produced rather than on the receiving end.
     *
     * @throws EncoderException if {@code size} is above {@code maxSize}
     */
    static void writeCount(ByteBuf buf, int size, int maxSize) {
        if (size > maxSize) {
            throw new EncoderException("Container size " + size + " is larger than the maximum allowed " + maxSize);
        }
        VarInts.write(buf, size);
    }

    // ===== Scalars =====

    StreamCodec<ByteBuf, Boolean> BOOL = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Boolean obj) {
            buf.writeBoolean(obj);
        }

        @Override
        public Boolean decode(ByteBuf buf) {
            return buf.readBoolean();
        }
    };

    StreamCodec<ByteBuf, Byte> BYTE = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Byte obj) {
            buf.writeByte(obj);
        }

        @Override
        public Byte decode(ByteBuf buf) {
            return buf.readByte();
        }
    };

    StreamCodec<ByteBuf, Short> SHORT = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Short obj) {
            buf.writeShort(obj);
        }

        @Override
        public Short decode(ByteBuf buf) {
            return buf.readShort();
        }
    };

    /**
     * An unsigned short, widened to {@code int} — {@code readUnsignedShort} semantics, so the value
     * decoded is never negative.
     */
    StreamCodec<ByteBuf, Integer> UNSIGNED_SHORT = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Integer obj) {
            buf.writeShort(obj);
        }

        @Override
        public Integer decode(ByteBuf buf) {
            return buf.readUnsignedShort();
        }
    };

    StreamCodec<ByteBuf, Character> CHAR = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Character obj) {
            buf.writeChar(obj);
        }

        @Override
        public Character decode(ByteBuf buf) {
            return buf.readChar();
        }
    };

    /**
     * A fixed four-byte integer ({@code writeInt}) — {@link #VAR_INT} is the variable-length one and
     * is what a length or an id wants, while this is what a raw bit pattern wants.
     */
    StreamCodec<ByteBuf, Integer> INT = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Integer obj) {
            buf.writeInt(obj);
        }

        @Override
        public Integer decode(ByteBuf buf) {
            return buf.readInt();
        }
    };

    /**
     * A VarInt ({@link VarInts}) — one byte for a value below 128, five at worst. This is what
     * lengths, ids and small numbers travel as.
     */
    StreamCodec<ByteBuf, Integer> VAR_INT = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Integer obj) {
            VarInts.write(buf, obj);
        }

        @Override
        public Integer decode(ByteBuf buf) {
            return VarInts.read(buf);
        }
    };

    /**
     * A fixed eight-byte long ({@code writeLong}) — {@link #VAR_LONG} is the variable-length one.
     */
    StreamCodec<ByteBuf, Long> LONG = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Long obj) {
            buf.writeLong(obj);
        }

        @Override
        public Long decode(ByteBuf buf) {
            return buf.readLong();
        }
    };

    /**
     * A VarLong — one byte for a value below 128, ten at worst.
     */
    StreamCodec<ByteBuf, Long> VAR_LONG = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Long obj) {
            VarInts.writeVarLong(buf, obj);
        }

        @Override
        public Long decode(ByteBuf buf) {
            return VarInts.readVarLong(buf);
        }
    };

    StreamCodec<ByteBuf, Float> FLOAT = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Float obj) {
            buf.writeFloat(obj);
        }

        @Override
        public Float decode(ByteBuf buf) {
            return buf.readFloat();
        }
    };

    StreamCodec<ByteBuf, Double> DOUBLE = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Double obj) {
            buf.writeDouble(obj);
        }

        @Override
        public Double decode(ByteBuf buf) {
            return buf.readDouble();
        }
    };

    /**
     * A UTF-8 string, via {@link FriendlyByteBuf#writeUtf(String)} — the one built-in that cannot be
     * written against a plain {@link ByteBuf}, because 1.20.1 keeps the string encoding (and its
     * length validation) on the Minecraft buffer while its varint primitives are what {@link VarInts}
     * restores. {@link #stringUtf8(int)} is the length-limited form.
     */
    StreamCodec<FriendlyByteBuf, String> STRING_UTF8 = stringUtf8(32767);

    /**
     * Two raw longs, most- then least-significant — the layout of {@code FriendlyByteBuf.writeUUID},
     * written against the plain buffer.
     */
    StreamCodec<ByteBuf, UUID> UUID = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, UUID obj) {
            buf.writeLong(obj.getMostSignificantBits());
            buf.writeLong(obj.getLeastSignificantBits());
        }

        @Override
        public UUID decode(ByteBuf buf) {
            return new UUID(buf.readLong(), buf.readLong());
        }
    };

    /**
     * A signed {@link BigInteger} as a VarInt-prefixed two's-complement byte array — the layout of
     * {@code BigInteger.toByteArray()}, so the sign lives in the payload.
     */
    StreamCodec<ByteBuf, BigInteger> BIG_INTEGER = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, BigInteger obj) {
            var bytes = obj.toByteArray();
            VarInts.write(buf, bytes.length);
            buf.writeBytes(bytes);
        }

        @Override
        public BigInteger decode(ByteBuf buf) {
            var bytes = new byte[VarInts.read(buf)];
            buf.readBytes(bytes);
            return new BigInteger(bytes);
        }
    };

    /**
     * A {@link BigDecimal} as its {@link #BIG_INTEGER} layout — the unscaled value's two's-complement bytes —
     * followed by the scale. The scale is a VarInt, not the one byte a stored {@code BigDecimal} has, because a
     * number written in JSON can carry any exponent: {@code 1e400} has scale -400 and {@code 1e-400} scale 400.
     */
    StreamCodec<ByteBuf, BigDecimal> BIG_DECIMAL = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, BigDecimal obj) {
            var bytes = obj.unscaledValue().toByteArray();
            VarInts.write(buf, bytes.length);
            buf.writeBytes(bytes);
            VarInts.write(buf, obj.scale());
        }

        @Override
        public BigDecimal decode(ByteBuf buf) {
            var bytes = new byte[VarInts.read(buf)];
            buf.readBytes(bytes);
            return new BigDecimal(new BigInteger(bytes), VarInts.read(buf));
        }
    };

    // ===== Arrays =====

    /**
     * A {@code boolean[]}: VarInt length, then one byte per element.
     */
    StreamCodec<ByteBuf, boolean[]> BOOLEAN_ARRAY = booleanArray(Integer.MAX_VALUE);

    /**
     * A {@code byte[]}: VarInt length, then the raw bytes.
     */
    StreamCodec<ByteBuf, byte[]> BYTE_ARRAY = byteArray(Integer.MAX_VALUE);

    /**
     * An {@code int[]} of fixed four-byte elements.
     */
    StreamCodec<ByteBuf, int[]> INT_ARRAY = intArray(Integer.MAX_VALUE);

    /**
     * An {@code int[]} of VarInt elements — the compact form, and the one small ids want.
     */
    StreamCodec<ByteBuf, int[]> VAR_INT_ARRAY = varIntArray(Integer.MAX_VALUE);

    /**
     * A {@code long[]} of fixed eight-byte elements.
     */
    StreamCodec<ByteBuf, long[]> LONG_ARRAY = longArray(Integer.MAX_VALUE);

    /**
     * A {@code long[]} of VarLong elements — the compact form.
     */
    StreamCodec<ByteBuf, long[]> VAR_LONG_ARRAY = varLongArray(Integer.MAX_VALUE);

    /**
     * A {@code short[]} of fixed two-byte elements.
     */
    StreamCodec<ByteBuf, short[]> SHORT_ARRAY = shortArray(Integer.MAX_VALUE);

    /**
     * A {@code char[]} of fixed two-byte elements.
     */
    StreamCodec<ByteBuf, char[]> CHAR_ARRAY = charArray(Integer.MAX_VALUE);

    /**
     * A {@code float[]} of fixed four-byte elements.
     */
    StreamCodec<ByteBuf, float[]> FLOAT_ARRAY = floatArray(Integer.MAX_VALUE);

    /**
     * A {@code double[]} of fixed eight-byte elements.
     */
    StreamCodec<ByteBuf, double[]> DOUBLE_ARRAY = doubleArray(Integer.MAX_VALUE);

    // ===== FastUtil primitive collections: written through the primitive iterators, nothing boxes =====

    /**
     * A FastUtil {@link IntList}: {@code VarInt} size, then each element as a {@code VarInt} — the
     * element format of {@link #VAR_INT_ARRAY}, fed from {@link IntList#intIterator()} and decoded
     * into an {@link IntArrayList}.
     *
     * <p>Hand-written rather than built with {@link #collection}: that helper is generic over the
     * element type, so every element would be boxed twice per round-trip (see the class documentation
     * on the boxing helper paths). The size prefix means an empty list is one byte.</p>
     */
    StreamCodec<ByteBuf, IntList> INT_LIST = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, IntList obj) {
            VarInts.write(buf, obj.size());
            for (var iterator = obj.intIterator(); iterator.hasNext(); ) {
                VarInts.write(buf, iterator.nextInt());
            }
        }

        @Override
        public IntList decode(ByteBuf buf) {
            var size = VarInts.read(buf);
            var list = new IntArrayList(Math.min(size, MAX_INITIAL_COLLECTION_SIZE));
            for (int i = 0; i < size; i++) {
                list.add(VarInts.read(buf));
            }
            return list;
        }
    };

    /**
     * A FastUtil {@link IntSet}: same wire form as {@link #INT_LIST}. A set travels in its iteration
     * order and is rebuilt into an {@link IntOpenHashSet} on the other side — only its content is part
     * of the contract, and change detection compares content hashes too, so a different iteration
     * order is not a difference.
     */
    StreamCodec<ByteBuf, IntSet> INT_SET = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, IntSet obj) {
            VarInts.write(buf, obj.size());
            for (var iterator = obj.intIterator(); iterator.hasNext(); ) {
                VarInts.write(buf, iterator.nextInt());
            }
        }

        @Override
        public IntSet decode(ByteBuf buf) {
            var size = VarInts.read(buf);
            var set = new IntOpenHashSet(Math.min(size, MAX_INITIAL_COLLECTION_SIZE));
            for (int i = 0; i < size; i++) {
                set.add(VarInts.read(buf));
            }
            return set;
        }
    };

    /**
     * A FastUtil {@link LongList}: {@code VarInt} size, then each element raw — the element format of
     * {@link #LONG_ARRAY}, fed from {@link LongList#longIterator()} and decoded into a
     * {@link LongArrayList}.
     */
    StreamCodec<ByteBuf, LongList> LONG_LIST = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, LongList obj) {
            VarInts.write(buf, obj.size());
            for (var iterator = obj.longIterator(); iterator.hasNext(); ) {
                buf.writeLong(iterator.nextLong());
            }
        }

        @Override
        public LongList decode(ByteBuf buf) {
            var size = VarInts.read(buf);
            var list = new LongArrayList(Math.min(size, MAX_INITIAL_COLLECTION_SIZE));
            for (int i = 0; i < size; i++) {
                list.add(buf.readLong());
            }
            return list;
        }
    };

    /**
     * A FastUtil {@link LongSet}: same wire form as {@link #LONG_LIST}, rebuilt into a
     * {@link LongOpenHashSet} on the other side (see {@link #INT_SET} on the iteration order).
     */
    StreamCodec<ByteBuf, LongSet> LONG_SET = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, LongSet obj) {
            VarInts.write(buf, obj.size());
            for (var iterator = obj.longIterator(); iterator.hasNext(); ) {
                buf.writeLong(iterator.nextLong());
            }
        }

        @Override
        public LongSet decode(ByteBuf buf) {
            var size = VarInts.read(buf);
            var set = new LongOpenHashSet(Math.min(size, MAX_INITIAL_COLLECTION_SIZE));
            for (int i = 0; i < size; i++) {
                set.add(buf.readLong());
            }
            return set;
        }
    };

    // ===== Limited-length array encoders =====
    // Each mirrors the matching constant above with a bound on the element count, so the allocation a
    // hostile payload can request is the bound the caller chose.

    /**
     * @param maxLength largest accepted element count, on both sides
     */
    static StreamCodec<ByteBuf, boolean[]> booleanArray(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, boolean[] obj) {
                writeCount(buf, obj.length, maxLength);
                for (var element : obj) {
                    buf.writeBoolean(element);
                }
            }

            @Override
            public boolean[] decode(ByteBuf buf) {
                var length = readCount(buf, maxLength);
                var array = new boolean[length];
                for (int i = 0; i < length; i++) {
                    array[i] = buf.readBoolean();
                }
                return array;
            }
        };
    }

    /**
     * @param maxLength largest accepted element count, on both sides
     */
    static StreamCodec<ByteBuf, byte[]> byteArray(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, byte[] obj) {
                writeCount(buf, obj.length, maxLength);
                buf.writeBytes(obj);
            }

            @Override
            public byte[] decode(ByteBuf buf) {
                var bytes = new byte[readCount(buf, maxLength)];
                buf.readBytes(bytes);
                return bytes;
            }
        };
    }

    /**
     * @param maxLength largest accepted element count, on both sides
     */
    static StreamCodec<ByteBuf, int[]> intArray(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, int[] obj) {
                writeCount(buf, obj.length, maxLength);
                for (var element : obj) {
                    buf.writeInt(element);
                }
            }

            @Override
            public int[] decode(ByteBuf buf) {
                var length = readCount(buf, maxLength);
                var array = new int[length];
                for (int i = 0; i < length; i++) {
                    array[i] = buf.readInt();
                }
                return array;
            }
        };
    }

    /**
     * @param maxLength largest accepted element count, on both sides
     */
    static StreamCodec<ByteBuf, int[]> varIntArray(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, int[] obj) {
                writeCount(buf, obj.length, maxLength);
                for (var element : obj) {
                    VarInts.write(buf, element);
                }
            }

            @Override
            public int[] decode(ByteBuf buf) {
                var length = readCount(buf, maxLength);
                var array = new int[length];
                for (int i = 0; i < length; i++) {
                    array[i] = VarInts.read(buf);
                }
                return array;
            }
        };
    }

    /**
     * @param maxLength largest accepted element count, on both sides
     */
    static StreamCodec<ByteBuf, long[]> longArray(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, long[] obj) {
                writeCount(buf, obj.length, maxLength);
                for (var element : obj) {
                    buf.writeLong(element);
                }
            }

            @Override
            public long[] decode(ByteBuf buf) {
                var length = readCount(buf, maxLength);
                var array = new long[length];
                for (int i = 0; i < length; i++) {
                    array[i] = buf.readLong();
                }
                return array;
            }
        };
    }

    /**
     * @param maxLength largest accepted element count, on both sides
     */
    static StreamCodec<ByteBuf, long[]> varLongArray(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, long[] obj) {
                writeCount(buf, obj.length, maxLength);
                for (var element : obj) {
                    VarInts.writeVarLong(buf, element);
                }
            }

            @Override
            public long[] decode(ByteBuf buf) {
                var length = readCount(buf, maxLength);
                var array = new long[length];
                for (int i = 0; i < length; i++) {
                    array[i] = VarInts.readVarLong(buf);
                }
                return array;
            }
        };
    }

    /**
     * @param maxLength largest accepted element count, on both sides
     */
    static StreamCodec<ByteBuf, short[]> shortArray(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, short[] obj) {
                writeCount(buf, obj.length, maxLength);
                for (var element : obj) {
                    buf.writeShort(element);
                }
            }

            @Override
            public short[] decode(ByteBuf buf) {
                var length = readCount(buf, maxLength);
                var array = new short[length];
                for (int i = 0; i < length; i++) {
                    array[i] = buf.readShort();
                }
                return array;
            }
        };
    }

    /**
     * @param maxLength largest accepted element count, on both sides
     */
    static StreamCodec<ByteBuf, char[]> charArray(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, char[] obj) {
                writeCount(buf, obj.length, maxLength);
                for (var element : obj) {
                    buf.writeChar(element);
                }
            }

            @Override
            public char[] decode(ByteBuf buf) {
                var length = readCount(buf, maxLength);
                var array = new char[length];
                for (int i = 0; i < length; i++) {
                    array[i] = buf.readChar();
                }
                return array;
            }
        };
    }

    /**
     * @param maxLength largest accepted element count, on both sides
     */
    static StreamCodec<ByteBuf, float[]> floatArray(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, float[] obj) {
                writeCount(buf, obj.length, maxLength);
                for (var element : obj) {
                    buf.writeFloat(element);
                }
            }

            @Override
            public float[] decode(ByteBuf buf) {
                var length = readCount(buf, maxLength);
                var array = new float[length];
                for (int i = 0; i < length; i++) {
                    array[i] = buf.readFloat();
                }
                return array;
            }
        };
    }

    /**
     * @param maxLength largest accepted element count, on both sides
     */
    static StreamCodec<ByteBuf, double[]> doubleArray(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, double[] obj) {
                writeCount(buf, obj.length, maxLength);
                for (var element : obj) {
                    buf.writeDouble(element);
                }
            }

            @Override
            public double[] decode(ByteBuf buf) {
                var length = readCount(buf, maxLength);
                var array = new double[length];
                for (int i = 0; i < length; i++) {
                    array[i] = buf.readDouble();
                }
                return array;
            }
        };
    }

    // ===== Limited-length strings =====

    /**
     * A UTF-8 string whose encoded form is bounded by {@code maxLength} bytes on both sides, through
     * {@link FriendlyByteBuf#writeUtf(String, int)} / {@link FriendlyByteBuf#readUtf(int)} — so the
     * vanilla validation (a too-long string fails on decode rather than being accepted) applies here
     * too. {@link #STRING_UTF8} is the same codec with vanilla's own bound of 32767.
     *
     * @param maxLength largest accepted encoded length in bytes
     */
    static StreamCodec<FriendlyByteBuf, String> stringUtf8(int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, String obj) {
                buf.writeUtf(obj, maxLength);
            }

            @Override
            public String decode(FriendlyByteBuf buf) {
                return buf.readUtf(maxLength);
            }
        };
    }

    // ===== Adapters =====

    /**
     * Adapts a Mojang DFU {@link Codec} through {@link JavaOps#INSTANCE}: the value it produces is a
     * carrier value, written as the id+payload bytes the disk form uses.
     *
     * <p>Failures surface as thrown exceptions from the {@code orElseThrow()} calls rather than as a
     * {@code DataResult} error, so this adapter is only safe for trusted data.</p>
     */
    static <T> StreamCodec<ByteBuf, T> fromCodec(Codec<T> codec) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, T obj) {
                var ops = JavaValueOps.INSTANCE;
                ops.writeValue(codec.encodeStart(JavaOps.INSTANCE, obj).result().orElseThrow(), buf);
            }

            @Override
            public T decode(ByteBuf buf) {
                var ops = JavaValueOps.INSTANCE;
                return codec.parse(JavaOps.INSTANCE, ops.readValue(buf)).result().orElseThrow();
            }
        };
    }

    /**
     * Adapts a {@link ValueCodec} by writing its id byte and payload inline
     * ({@link ValueOps#writeValue(Object, ByteBuf)}) — the native twin of
     * the retired Data codec bridge, and the same layout, since the ids are the wire ids.
     *
     * <p>The version is the one the codec is handed: the ops is {@link JavaValueOps#create(int)}, so a
     * codec whose reading depends on {@link ValueOps#dataVersion()} sees {@code version} and not the
     * carrier's default. A wire value carries no version of its own — the bytes are written and read by
     * this same codec — so the caller passes the version its data is written at, which is the current
     * one: {@code 0} for a codec with no versioned layouts, a mod's own {@code VERSION} otherwise. A
     * defaulted version would silently hand a codec that adapts an old shape the branch for that old
     * shape while it decodes a value written today.</p>
     */
    static <T> StreamCodec<ByteBuf, T> fromValueCodec(int version, ValueCodec<T> codec) {
        var ops = JavaValueOps.create(version);
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, T obj) {
                ops.writeValue(codec.encode(ops, obj), buf);
            }

            @Override
            public T decode(ByteBuf buf) {
                return codec.decode(ops, ops.readValue(buf));
            }
        };
    }

    /**
     * Resolves the network-side codec of a type: the {@link StreamCodec} half of what
     * {@link com.gto.datasynclib.DataSyncCodec#get(Class)} holds, so a caller that only serializes to
     * the wire works with the bare half the combined codec was built from instead of the wrapper.
     *
     * <p>The lookup rules are the combined registry's own: an exact class match, enums and object
     * arrays generated on demand, and a primitive resolved as its wrapper. A type without a codec —
     * including a type whose codec only exists on a {@code @Codec} annotation — resolves to
     * {@code null}.</p>
     *
     * @param type the class to resolve
     * @return the network-side codec, or {@code null} if the type has none
     */
    @Nullable
    static <T> StreamCodec<? super FriendlyByteBuf, T> get(Class<T> type) {
        var codec = DataSyncCodec.get(type);
        if (codec == null) return null;
        return codec.toStreamCodec();
    }

    // ===== Container builders =====

    /**
     * Map codec: writes a VarInt size followed by the key/value pairs.
     * Decoding reads exactly {@code size} pairs, so no empty-container special case is needed.
     * Keys and values travel as boxed generic types.
     *
     * @param function factory that creates the map by expected size (e.g. {@code HashMap::new})
     */
    static <B extends ByteBuf, K, V, M extends Map<K, V>> StreamCodec<B, M> map(IntFunction<M> function, StreamCodec<? super B, K> keyCodec, StreamCodec<? super B, V> valueCodec) {
        return map(function, keyCodec, valueCodec, Integer.MAX_VALUE);
    }

    /**
     * {@link #map(IntFunction, StreamCodec, StreamCodec)} with a bound on the pair count: a payload
     * declaring more than {@code maxSize} pairs fails instead of allocating for it.
     *
     * @param maxSize largest accepted pair count, on both sides
     */
    static <B extends ByteBuf, K, V, M extends Map<K, V>> StreamCodec<B, M> map(IntFunction<M> function, StreamCodec<? super B, K> keyCodec, StreamCodec<? super B, V> valueCodec, int maxSize) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, M obj) {
                writeCount(buf, obj.size(), maxSize);
                obj.forEach((k, v) -> {
                    keyCodec.encode(buf, k);
                    valueCodec.encode(buf, v);
                });
            }

            @Override
            public M decode(B buf) {
                var size = readCount(buf, maxSize);
                var map = function.apply(Math.min(size, MAX_INITIAL_COLLECTION_SIZE));
                for (int i = 0; i < size; i++) {
                    map.put(keyCodec.decode(buf), valueCodec.decode(buf));
                }
                return map;
            }
        };
    }

    /**
     * Collection codec: writes a VarInt size followed by the elements, and reads exactly that
     * many elements back (no empty-container special case). Elements travel as boxed generic
     * types.
     *
     * @param function factory that creates the collection by expected size (e.g. {@code ArrayList::new})
     */
    static <B extends ByteBuf, E, C extends Collection<E>> StreamCodec<B, C> collection(IntFunction<C> function, StreamCodec<? super B, E> elementCodec) {
        return collection(function, elementCodec, Integer.MAX_VALUE);
    }

    /**
     * {@link #collection(IntFunction, StreamCodec)} with a bound on the element count: a payload
     * declaring more than {@code maxSize} elements fails instead of allocating for it.
     *
     * @param maxSize largest accepted element count, on both sides
     */
    static <B extends ByteBuf, E, C extends Collection<E>> StreamCodec<B, C> collection(IntFunction<C> function, StreamCodec<? super B, E> elementCodec, int maxSize) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, C obj) {
                writeCount(buf, obj.size(), maxSize);
                obj.forEach(element -> elementCodec.encode(buf, element));
            }

            @Override
            public C decode(B buf) {
                var size = readCount(buf, maxSize);
                var collection = function.apply(Math.min(size, MAX_INITIAL_COLLECTION_SIZE));
                for (int i = 0; i < size; i++) {
                    collection.add(elementCodec.decode(buf));
                }
                return collection;
            }
        };
    }

    /**
     * {@code List} codec backed by {@link ArrayList} — {@link #collection} with the factory filled
     * in, for the common case.
     */
    static <B extends ByteBuf, E> StreamCodec<B, List<E>> list(StreamCodec<? super B, E> elementCodec) {
        return list(elementCodec, Integer.MAX_VALUE);
    }

    /**
     * {@link #list(StreamCodec)} with a bound on the element count.
     *
     * @param maxSize largest accepted element count, on both sides
     */
    static <B extends ByteBuf, E> StreamCodec<B, List<E>> list(StreamCodec<? super B, E> elementCodec, int maxSize) {
        IntFunction<List<E>> factory = ArrayList::new;
        return collection(factory, elementCodec, maxSize);
    }

    /**
     * {@code Set} codec backed by a FastUtil {@link ReferenceOpenHashSet} — reference identity, so two
     * equal-but-distinct objects both survive the round-trip, and the iteration order is not part of
     * the contract (see {@link #INT_SET}).
     */
    static <B extends ByteBuf, E> StreamCodec<B, Set<E>> set(StreamCodec<? super B, E> elementCodec) {
        return set(elementCodec, Integer.MAX_VALUE);
    }

    /**
     * {@link #set(StreamCodec)} with a bound on the element count.
     *
     * @param maxSize largest accepted element count, on both sides
     */
    static <B extends ByteBuf, E> StreamCodec<B, Set<E>> set(StreamCodec<? super B, E> elementCodec, int maxSize) {
        IntFunction<Set<E>> factory = ReferenceOpenHashSet::new;
        return collection(factory, elementCodec, maxSize);
    }

    // ===== Codec operations: the fluent form of the builders below =====
    // These wrap a codec instead of taking one, so they are read as CODEC.apply(operation) — see
    // StreamCodec#apply. They cover the containers whose factory the caller does not have to supply
    // (collection, list, set, array) plus the three nullable adapters; a map still needs its factory,
    // so it stays a plain builder.

    /**
     * {@link #collection(IntFunction, StreamCodec)}, as a {@link StreamCodec.CodecOperation} — the
     * factory-first form applied fluently to the element codec:
     * {@code STRING_UTF8.apply(ByteBufCodecs.collection(LinkedHashSet::new))}.
     */
    static <B extends ByteBuf, E, C extends Collection<E>> StreamCodec.CodecOperation<B, E, C> collection(IntFunction<C> function) {
        return codec -> collection(function, codec);
    }

    /**
     * {@link #list(StreamCodec)}, as a {@link StreamCodec.CodecOperation}:
     * {@code STRING_UTF8.apply(ByteBufCodecs.list())} is the unbounded {@code List<String>} codec.
     */
    static <B extends ByteBuf, E> StreamCodec.CodecOperation<B, E, List<E>> list() {
        return list(Integer.MAX_VALUE);
    }

    /**
     * {@link #list(StreamCodec, int)}, as a {@link StreamCodec.CodecOperation}:
     * {@code STRING_UTF8.apply(ByteBufCodecs.list(8))}.
     *
     * @param maxSize largest accepted element count, on both sides
     */
    static <B extends ByteBuf, E> StreamCodec.CodecOperation<B, E, List<E>> list(int maxSize) {
        IntFunction<List<E>> factory = ArrayList::new;
        return codec -> collection(factory, codec, maxSize);
    }

    /**
     * {@link #set(StreamCodec)}, as a {@link StreamCodec.CodecOperation}:
     * {@code STRING_UTF8.apply(ByteBufCodecs.set())} is the unbounded reference-backed
     * {@code Set<String>} codec.
     */
    static <B extends ByteBuf, E> StreamCodec.CodecOperation<B, E, Set<E>> set() {
        return set(Integer.MAX_VALUE);
    }

    /**
     * {@link #set(StreamCodec, int)}, as a {@link StreamCodec.CodecOperation}.
     *
     * @param maxSize largest accepted element count, on both sides
     */
    static <B extends ByteBuf, E> StreamCodec.CodecOperation<B, E, Set<E>> set(int maxSize) {
        IntFunction<Set<E>> factory = ReferenceOpenHashSet::new;
        return codec -> collection(factory, codec, maxSize);
    }

    /**
     * {@link #array(Class, StreamCodec)} as a {@link StreamCodec.CodecOperation}, allocating through
     * the component type: {@code STRING_UTF8.apply(ByteBufCodecs.array(String.class))} is the
     * unbounded {@code String[]} codec.
     *
     * @param type the array's component type, for the reflective allocation
     */
    static <B extends ByteBuf, T> StreamCodec.CodecOperation<B, T, T[]> array(Class<T> type) {
        return array(type, Integer.MAX_VALUE);
    }

    /**
     * {@link #array(Class, StreamCodec, int)} as a {@link StreamCodec.CodecOperation} — the bounded
     * form of {@link #array(Class)}.
     *
     * @param maxLength largest accepted element count, on both sides
     */
    static <B extends ByteBuf, T> StreamCodec.CodecOperation<B, T, T[]> array(Class<T> type, int maxLength) {
        return codec -> array(type, codec, maxLength);
    }

    /**
     * {@link #array(IntFunction, StreamCodec)} as a {@link StreamCodec.CodecOperation}, taking the
     * array factory itself: {@code STRING_UTF8.apply(ByteBufCodecs.array(String[]::new))}.
     *
     * @param factory creates an array of the requested length
     */
    static <B extends ByteBuf, T> StreamCodec.CodecOperation<B, T, T[]> array(IntFunction<T[]> factory) {
        return array(factory, Integer.MAX_VALUE);
    }

    /**
     * {@link #array(IntFunction, StreamCodec, int)} as a {@link StreamCodec.CodecOperation} — the
     * bounded form of {@link #array(IntFunction)}.
     *
     * @param maxLength largest accepted element count, on both sides
     */
    static <B extends ByteBuf, T> StreamCodec.CodecOperation<B, T, T[]> array(IntFunction<T[]> factory, int maxLength) {
        return codec -> array(factory, codec, maxLength);
    }

    /**
     * {@link #optional(StreamCodec)} as a {@link StreamCodec.CodecOperation} — the element codec made
     * nullable, read fluently as {@code STRING_UTF8.apply(ByteBufCodecs.optional())}.
     */
    static <B extends ByteBuf, T> StreamCodec.CodecOperation<B, T, T> optional() {
        return ByteBufCodecs::optional;
    }

    /**
     * {@link #optional(StreamCodec, Object)} as a {@link StreamCodec.CodecOperation}:
     * {@code STRING_UTF8.apply(ByteBufCodecs.optional("d"))}.
     *
     * @param defaultValue the value that is written as absent
     */
    static <B extends ByteBuf, T> StreamCodec.CodecOperation<B, T, T> optional(T defaultValue) {
        return codec -> ByteBufCodecs.optional(codec, defaultValue);
    }

    /**
     * {@link #optional(StreamCodec, Supplier)} as a {@link StreamCodec.CodecOperation}.
     *
     * @param defaultSupplier supplies the value that is written as absent
     */
    static <B extends ByteBuf, T> StreamCodec.CodecOperation<B, T, T> optional(Supplier<? extends T> defaultSupplier) {
        return codec -> ByteBufCodecs.optional(codec, defaultSupplier);
    }

    /**
     * Object-array codec: writes a VarInt length followed by the elements, and allocates the array
     * from the declared length. Elements travel as boxed generic types; a <em>primitive</em> array has
     * its own primitive-backed codec ({@code INT_ARRAY}, {@code LONG_ARRAY}, …) and does not need
     * this.
     *
     * @param type the array's component type, for the reflective allocation
     */
    static <B extends ByteBuf, T> StreamCodec<B, T[]> array(Class<T> type, StreamCodec<? super B, T> elementCodec) {
        return array(type, elementCodec, Integer.MAX_VALUE);
    }

    /**
     * {@link #array(Class, StreamCodec)} with a bound on the element count — the array flavour of
     * {@link #readCount(ByteBuf, int)}, without which a corrupt length allocates an arbitrarily large
     * array.
     *
     * @param maxLength largest accepted element count, on both sides
     */
    static <B extends ByteBuf, T> StreamCodec<B, T[]> array(Class<T> type, StreamCodec<? super B, T> elementCodec, int maxLength) {
        @SuppressWarnings("unchecked")
        IntFunction<T[]> factory = length -> (T[]) Array.newInstance(type, length);
        return array(factory, elementCodec, maxLength);
    }

    /**
     * Object-array codec taking the array factory itself, so the caller can pick the concrete array
     * type ({@code MyType[]::new}) instead of naming a component class.
     *
     * @param factory creates an array of the requested length
     */
    static <B extends ByteBuf, T> StreamCodec<B, T[]> array(IntFunction<T[]> factory, StreamCodec<? super B, T> elementCodec) {
        return array(factory, elementCodec, Integer.MAX_VALUE);
    }

    /**
     * {@link #array(IntFunction, StreamCodec)} with a bound on the element count.
     *
     * @param maxLength largest accepted element count, on both sides
     */
    static <B extends ByteBuf, T> StreamCodec<B, T[]> array(IntFunction<T[]> factory, StreamCodec<? super B, T> elementCodec, int maxLength) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, T[] obj) {
                writeCount(buf, obj.length, maxLength);
                for (var element : obj) {
                    elementCodec.encode(buf, element);
                }
            }

            @Override
            public T[] decode(B buf) {
                var length = readCount(buf, maxLength);
                var array = factory.apply(length);
                for (int i = 0; i < length; i++) {
                    array[i] = elementCodec.decode(buf);
                }
                return array;
            }
        };
    }

    // ===== Value adapters =====

    /**
     * A codec for a nullable value, as a builder over an arbitrary codec.
     *
     * <p>A {@code null} value is written as a {@code false} flag and decodes back to {@code null};
     * anything else costs one extra leading boolean. This is the null-based form the whole library
     * uses — see the class documentation on how it differs from vanilla's {@code Optional} form.</p>
     *
     * @param codec codec for the present (non-{@code null}) value
     * @param <T>   the value type
     */
    static <B extends ByteBuf, T> StreamCodec<B, T> optional(StreamCodec<? super B, T> codec) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, T obj) {
                if (obj == null) {
                    buf.writeBoolean(false);
                } else {
                    buf.writeBoolean(true);
                    codec.encode(buf, obj);
                }
            }

            @Override
            public T decode(B buf) {
                return buf.readBoolean() ? codec.decode(buf) : null;
            }
        };
    }

    /**
     * {@link #optional(StreamCodec)}, with a value that is written as absent instead of {@code null}.
     *
     * @param codec        codec for the present value
     * @param defaultValue the value that is written as absent; a {@code null} default degrades to
     *                     plain {@link #optional(StreamCodec)}
     * @param <T>          the value type
     */
    static <B extends ByteBuf, T> StreamCodec<B, T> optional(StreamCodec<? super B, T> codec, T defaultValue) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, T obj) {
                if (obj == null || Objects.equals(obj, defaultValue)) {
                    buf.writeBoolean(false);
                } else {
                    buf.writeBoolean(true);
                    codec.encode(buf, obj);
                }
            }

            @Override
            public T decode(B buf) {
                return buf.readBoolean() ? codec.decode(buf) : defaultValue;
            }
        };
    }

    /**
     * {@link #optional(StreamCodec, Object)} with the default produced by a supplier, for a default
     * that is not a constant (a fresh collection, a value read from config, …).
     *
     * <p>A bare lambda or method reference is ambiguous between this overload and
     * {@link #optional(StreamCodec, Object)} — {@code optional(codec, ArrayList::new)} does not
     * compile — so pass a typed {@code Supplier<T>} variable or cast the lambda.</p>
     *
     * @param codec           codec for the present value
     * @param defaultSupplier supplies the value that is written as absent
     * @param <T>             the value type
     */
    static <B extends ByteBuf, T> StreamCodec<B, T> optional(StreamCodec<? super B, T> codec, Supplier<? extends T> defaultSupplier) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, T obj) {
                if (obj == null || Objects.equals(obj, defaultSupplier.get())) {
                    buf.writeBoolean(false);
                } else {
                    buf.writeBoolean(true);
                    codec.encode(buf, obj);
                }
            }

            @Override
            public T decode(B buf) {
                return buf.readBoolean() ? codec.decode(buf) : defaultSupplier.get();
            }
        };
    }

    /**
     * A codec for either of two branches: a leading boolean says which side follows, then that side's
     * own codec writes the payload — the network counterpart of Mojang's
     * {@link Either}-flavoured {@code Codec} combinators.
     *
     * @param leftCodec  codec for the left branch, written when the flag is {@code true}
     * @param rightCodec codec for the right branch
     */
    static <B extends ByteBuf, L, R> StreamCodec<B, Either<L, R>> either(StreamCodec<? super B, L> leftCodec, StreamCodec<? super B, R> rightCodec) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, Either<L, R> obj) {
                var left = obj.left();
                if (left.isPresent()) {
                    buf.writeBoolean(true);
                    leftCodec.encode(buf, left.get());
                } else {
                    buf.writeBoolean(false);
                    rightCodec.encode(buf, obj.right().orElseThrow());
                }
            }

            @Override
            public Either<L, R> decode(B buf) {
                return buf.readBoolean() ? Either.left(leftCodec.decode(buf)) : Either.right(rightCodec.decode(buf));
            }
        };
    }

    /**
     * A codec for a value with a small dense id space: the wire carries the id as a VarInt, and the
     * two functions map between id and value — the shape of a registry or a fixed enum, without
     * building a map.
     *
     * @param indexToValue id to value, used on decode
     * @param valueToIndex value to id, used on encode
     */
    static <B extends ByteBuf, T> StreamCodec<B, T> idMapper(IntFunction<T> indexToValue, ToIntFunction<T> valueToIndex) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, T obj) {
                VarInts.write(buf, valueToIndex.applyAsInt(obj));
            }

            @Override
            public T decode(B buf) {
                return indexToValue.apply(VarInts.read(buf));
            }
        };
    }
}
