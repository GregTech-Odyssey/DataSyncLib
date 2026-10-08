package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.DataOps;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * The <strong>concrete-buffer</strong> half of the codec toolkit: every built-in codec, plus the
 * builders and adapters that have to name a real buffer.
 *
 * <p>{@link StreamCodec} itself is buffer-agnostic — it composes codecs for a buffer it never
 * mentions, which is what keeps a codec written for one buffer usable wherever a broader one is
 * expected. Everything that cannot be written that way lives here instead:</p>
 * <ul>
 *   <li>the constants: the numeric family is {@code StreamCodec<ByteBuf, …>} — the least a buffer can
 *       be while still carrying a VarInt ({@link VarInts}) — while {@link #STRING_CODEC} and the
 *       Minecraft value types ({@link com.gto.datasynclib.util.StreamCodecs}) need
 *       {@link FriendlyByteBuf}'s own API</li>
 *   <li>{@link #optional}, {@link #map}, {@link #collection} and {@link #array}, which write a size
 *       or a presence flag and therefore need a real buffer</li>
 *   <li>{@link #of(DataCodec)} / {@link #of(Codec)}, which allocate a buffer of their own to carry the
 *       other path's payload, and {@link #get(Class)}, the network-side registry lookup</li>
 * </ul>
 */
public interface ByteStreamCodec {
    /**
     * Adapts a disk codec by writing its {@link Data} payload inline
     * ({@link Data#writeData(ByteBuf, Data)}) — same layout as the persistence form,
     * without the extra length-prefixed byte array used by
     * {@link com.gto.datasynclib.DataSyncCodec#of(DataCodec)}.
     */
    static <T> StreamCodec<ByteBuf, T> of(DataCodec<T> codec) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, T obj) {
                Data.writeData(buf, codec.encode(obj));
            }

            @Override
            public T decode(ByteBuf buf) {
                return codec.decode(Data.readData(buf));
            }
        };
    }

    /**
     * Adapts a Mojang DFU {@link Codec} through {@link DataOps#INSTANCE}.
     *
     * <p>Failures surface as thrown exceptions from the {@code orElseThrow()} calls rather than
     * as a {@code DataResult} error, so this adapter is only safe for trusted data.</p>
     */
    static <T> StreamCodec<ByteBuf, T> of(Codec<T> codec) {
        return new StreamCodec<>() {

            @Override
            public void encode(ByteBuf buf, T obj) {
                Data.writeData(buf, codec.encodeStart(DataOps.INSTANCE, obj).result().orElseThrow());
            }

            @Override
            public T decode(ByteBuf buf) {
                return codec.decode(DataOps.INSTANCE, Data.readData(buf)).result().orElseThrow().getFirst();
            }
        };
    }

    /**
     * Map codec: writes a VarInt size followed by the key/value pairs.
     * Decoding reads exactly {@code size} pairs, so no empty-container special case is needed.
     * Keys and values travel as boxed generic types.
     *
     * @param function factory that creates the map by expected size (e.g. {@code HashMap::new})
     */
    static <B extends ByteBuf, K, V, M extends Map<K, V>> StreamCodec<B, M> map(IntFunction<M> function, StreamCodec<? super B, K> keyCodec, StreamCodec<? super B, V> valueCodec) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, M obj) {
                VarInts.write(buf, obj.size());
                obj.forEach((k, v) -> {
                    keyCodec.encode(buf, k);
                    valueCodec.encode(buf, v);
                });
            }

            @Override
            public M decode(B buf) {
                int size = VarInts.read(buf);
                var map = function.apply(size);
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
    static <B extends ByteBuf, T, C extends Collection<T>> StreamCodec<B, C> collection(IntFunction<C> function, StreamCodec<? super B, T> codec) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, C obj) {
                VarInts.write(buf, obj.size());
                obj.forEach(o -> codec.encode(buf, o));
            }

            @Override
            public C decode(B buf) {
                int size = VarInts.read(buf);
                var set = function.apply(size);
                for (int i = 0; i < size; i++) set.add(codec.decode(buf));
                return set;
            }
        };
    }

    /**
     * Object-array codec: writes a VarInt length followed by the elements. The length comes from
     * the payload, so a corrupt or hostile size can allocate an arbitrarily large array.
     * Elements travel as boxed generic types; a <em>primitive</em> array has its own
     * primitive-backed codec ({@code INTS_CODEC}, {@code LONGS_CODEC}, …) and does not need this.
     */
    static <B extends ByteBuf, T> StreamCodec<B, T[]> array(Class<T> type, StreamCodec<? super B, T> codec) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, T[] obj) {
                VarInts.write(buf, obj.length);
                for (T o : obj) {
                    codec.encode(buf, o);
                }
            }

            @Override
            public T[] decode(B buf) {
                int size = VarInts.read(buf);
                var array = (T[]) Array.newInstance(type, size);
                for (int i = 0; i < size; i++) array[i] = codec.decode(buf);
                return array;
            }
        };
    }

    /**
     * {@link #optional()}, as a builder over an arbitrary codec.
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
     * {@link #optional(Object)}, as a builder over an arbitrary codec.
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
     * {@link #optional(Supplier)}, as a builder over an arbitrary codec.
     *
     * <p>As on the instance form, a bare lambda or method reference is ambiguous between this
     * overload and {@link #optional(StreamCodec, Object)} — {@code optional(codec, ArrayList::new)}
     * does not compile — so pass a typed {@code Supplier<T>} variable or cast the lambda.</p>
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
        // The registry's network halves are ByteStreamCodecs (that is what register/of accept), so the
        // only thing spelled out here is what a capture variable's lower bound does not tell javac.
        return codec.toStreamCodec();
    }

    StreamCodec<ByteBuf, Boolean> BOOLEAN_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Boolean obj) {
            buf.writeBoolean(obj);
        }

        @Override
        public Boolean decode(ByteBuf buf) {
            return buf.readBoolean();
        }
    };

    StreamCodec<ByteBuf, Byte> BYTE_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Byte obj) {
            buf.writeByte(obj);
        }

        @Override
        public Byte decode(ByteBuf buf) {
            return buf.readByte();
        }
    };

    StreamCodec<ByteBuf, Short> SHORT_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Short obj) {
            buf.writeShort(obj);
        }

        @Override
        public Short decode(ByteBuf buf) {
            return buf.readShort();
        }
    };

    StreamCodec<ByteBuf, Character> CHAR_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Character obj) {
            buf.writeChar(obj);
        }

        @Override
        public Character decode(ByteBuf buf) {
            return buf.readChar();
        }
    };

    StreamCodec<ByteBuf, Integer> INT_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Integer obj) {
            VarInts.write(buf, obj);
        }

        @Override
        public Integer decode(ByteBuf buf) {
            return VarInts.read(buf);
        }
    };

    StreamCodec<ByteBuf, Long> LONG_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Long obj) {
            buf.writeLong(obj);
        }

        @Override
        public Long decode(ByteBuf buf) {
            return buf.readLong();
        }
    };

    StreamCodec<ByteBuf, Float> FLOAT_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, Float obj) {
            buf.writeFloat(obj);
        }

        @Override
        public Float decode(ByteBuf buf) {
            return buf.readFloat();
        }
    };

    StreamCodec<ByteBuf, Double> DOUBLE_CODEC = new StreamCodec<>() {

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
     * written against a plain {@link ByteBuf}, because 1.20.1 keeps the string encoding on the
     * Minecraft buffer while its varint primitives are what {@link VarInts} restores.
     */
    StreamCodec<FriendlyByteBuf, String> STRING_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, String obj) {
            buf.writeUtf(obj);
        }

        @Override
        public String decode(FriendlyByteBuf buf) {
            return buf.readUtf();
        }
    };

    StreamCodec<ByteBuf, BigInteger> BIG_INTEGER_CODEC = new StreamCodec<>() {

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

    StreamCodec<ByteBuf, boolean[]> BOOLEANS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, boolean[] obj) {
            VarInts.write(buf, obj.length);
            for (var b : obj) {
                buf.writeBoolean(b);
            }
        }

        @Override
        public boolean[] decode(ByteBuf buf) {
            var length = VarInts.read(buf);
            var booleans = new boolean[length];
            for (int i = 0; i < length; i++) {
                booleans[i] = buf.readBoolean();
            }
            return booleans;
        }
    };

    StreamCodec<ByteBuf, byte[]> BYTES_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, byte[] obj) {
            VarInts.write(buf, obj.length);
            buf.writeBytes(obj);
        }

        @Override
        public byte[] decode(ByteBuf buf) {
            var bytes = new byte[VarInts.read(buf)];
            buf.readBytes(bytes);
            return bytes;
        }
    };

    StreamCodec<ByteBuf, int[]> INTS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, int[] obj) {
            VarInts.write(buf, obj.length);
            for (var i : obj) {
                VarInts.write(buf, i);
            }
        }

        @Override
        public int[] decode(ByteBuf buf) {
            var length = VarInts.read(buf);
            var ints = new int[length];
            for (int i = 0; i < length; i++) {
                ints[i] = VarInts.read(buf);
            }
            return ints;
        }
    };

    StreamCodec<ByteBuf, long[]> LONGS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, long[] obj) {
            VarInts.write(buf, obj.length);
            for (var value : obj) {
                buf.writeLong(value);
            }
        }

        @Override
        public long[] decode(ByteBuf buf) {
            var length = VarInts.read(buf);
            var longs = new long[length];
            for (int i = 0; i < length; i++) {
                longs[i] = buf.readLong();
            }
            return longs;
        }
    };

    StreamCodec<ByteBuf, short[]> SHORTS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, short[] obj) {
            VarInts.write(buf, obj.length);
            for (var i : obj) {
                buf.writeShort(i);
            }
        }

        @Override
        public short[] decode(ByteBuf buf) {
            var length = VarInts.read(buf);
            var shorts = new short[length];
            for (int i = 0; i < length; i++) {
                shorts[i] = buf.readShort();
            }
            return shorts;
        }
    };

    StreamCodec<ByteBuf, char[]> CHARS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, char[] obj) {
            VarInts.write(buf, obj.length);
            for (var i : obj) {
                buf.writeChar(i);
            }
        }

        @Override
        public char[] decode(ByteBuf buf) {
            var length = VarInts.read(buf);
            var chars = new char[length];
            for (int i = 0; i < length; i++) {
                chars[i] = buf.readChar();
            }
            return chars;
        }
    };

    StreamCodec<ByteBuf, float[]> FLOATS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, float[] obj) {
            VarInts.write(buf, obj.length);
            for (var i : obj) {
                buf.writeFloat(i);
            }
        }

        @Override
        public float[] decode(ByteBuf buf) {
            var length = VarInts.read(buf);
            var floats = new float[length];
            for (int i = 0; i < length; i++) {
                floats[i] = buf.readFloat();
            }
            return floats;
        }
    };

    StreamCodec<ByteBuf, double[]> DOUBLES_CODEC = new StreamCodec<>() {

        @Override
        public void encode(ByteBuf buf, double[] obj) {
            VarInts.write(buf, obj.length);
            for (var i : obj) {
                buf.writeDouble(i);
            }
        }

        @Override
        public double[] decode(ByteBuf buf) {
            var length = VarInts.read(buf);
            var doubles = new double[length];
            for (int i = 0; i < length; i++) {
                doubles[i] = buf.readDouble();
            }
            return doubles;
        }
    };

    /**
     * Two raw longs, most- then least-significant — the layout of {@code FriendlyByteBuf.writeUUID},
     * written against the plain buffer.
     */
    StreamCodec<ByteBuf, UUID> UUID_CODEC = new StreamCodec<>() {

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

    // ---- FastUtil primitive collections: written through the primitive iterators, nothing boxes ----

    /**
     * A FastUtil {@link IntList}: {@code VarInt} size, then each element as a {@code VarInt} — the
     * element format of {@link #INTS_CODEC}, fed from {@link IntList#intIterator()} and decoded into
     * an {@link IntArrayList}.
     *
     * <p>Hand-written rather than built with {@link #collection}: that helper is generic over the
     * element type, so every element would be boxed twice per round-trip (see the class documentation
     * on the boxing helper paths). The size prefix means an empty list is one byte.</p>
     */
    StreamCodec<ByteBuf, IntList> INT_LIST_CODEC = new StreamCodec<>() {

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
            var list = new IntArrayList(size);
            for (int i = 0; i < size; i++) {
                list.add(VarInts.read(buf));
            }
            return list;
        }
    };

    /**
     * A FastUtil {@link IntSet}: same wire form as {@link #INT_LIST_CODEC}. A set travels in its
     * iteration order and is rebuilt into an {@link IntOpenHashSet} on the other side — only its
     * content is part of the contract, and change detection compares content hashes too, so a
     * different iteration order is not a difference.
     */
    StreamCodec<ByteBuf, IntSet> INT_SET_CODEC = new StreamCodec<>() {

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
            var set = new IntOpenHashSet(size);
            for (int i = 0; i < size; i++) {
                set.add(VarInts.read(buf));
            }
            return set;
        }
    };

    /**
     * A FastUtil {@link LongList}: {@code VarInt} size, then each element raw — the element format of
     * {@link #LONGS_CODEC}, fed from {@link LongList#longIterator()} and decoded into a
     * {@link LongArrayList}.
     */
    StreamCodec<ByteBuf, LongList> LONG_LIST_CODEC = new StreamCodec<>() {

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
            var list = new LongArrayList(size);
            for (int i = 0; i < size; i++) {
                list.add(buf.readLong());
            }
            return list;
        }
    };

    /**
     * A FastUtil {@link LongSet}: same wire form as {@link #LONG_LIST_CODEC}, rebuilt into a
     * {@link LongOpenHashSet} on the other side (see {@link #INT_SET_CODEC} on the iteration order).
     */
    StreamCodec<ByteBuf, LongSet> LONG_SET_CODEC = new StreamCodec<>() {

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
            var set = new LongOpenHashSet(size);
            for (int i = 0; i < size; i++) {
                set.add(buf.readLong());
            }
            return set;
        }
    };
}