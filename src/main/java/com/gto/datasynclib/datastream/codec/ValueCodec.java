package com.gto.datasynclib.datastream.codec;

import com.mojang.datafixers.util.*;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Encodes a value into a carrier and decodes it back, with the carrier supplied by a {@link ValueOps}
 * instead of being named — the {@code Codec<T>} / {@code DynamicOps<T>} split of Mojang's DFU, over
 * this library's own type set.
 *
 * <p>Both halves take the ops as their first argument, which is what keeps a codec free of the
 * carrier: {@link #encode(ValueOps, Object) encode} turns a {@code T} into whatever the ops builds,
 * {@link #decode(ValueOps, Object) decode} turns one of those back into a {@code T}, and the same
 * codec instance runs on every implementation of {@link ValueOps}. The ops is passed per call rather
 * than stored, so a codec is a plain stateless object (or lambda) that can be shared and cached.</p>
 *
 * <p>Unlike {@link StreamCodec}, nothing here is tied to a buffer. {@link JavaValueOps} is the carrier this library ships, and any other
 * {@link ValueOps} implementation works with the same codec.</p>
 *
 * <p>The three directions are split as they are everywhere else in the library:
 * {@link ValueEncoder} is the write half, {@link ValueDecoder} the read half, and this interface is
 * both — {@link #of(ValueEncoder, ValueDecoder)} pairs two independent halves, the way
 * {@code StreamCodec.of} does.</p>
 *
 * @param <T> the type this codec encodes and decodes
 */
public interface ValueCodec<T> extends ValueDecoder<T>, ValueEncoder<T> {

    /**
     * Pairs an independent encoder and decoder into a codec. Both receive the ops as their first
     * argument, so neither has to capture one.
     */
    static <T> ValueCodec<T> of(ValueEncoder<? super T> encoder, ValueDecoder<? extends T> decoder) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return encoder.encode(ops, value);
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return decoder.decode(ops, data);
            }
        };
    }

    /**
     * A codec for a carrier's opaque value: {@link ValueOps#createSelf(Object)} on the way out and
     * {@link ValueOps#getSelf(Object)} on the way back, with no encoding in between.
     *
     * <p>It is what makes a {@link ValueOps.Type#SELF} value usable — the ops cannot store such a
     * value, but a codec that wraps one in this codec hands the value itself to the layer above,
     * which is where the knowledge of how to serialize it lives.</p>
     */
    static <T> ValueCodec<T> self() {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return ops.createSelf(value);
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return ops.getSelf(data);
            }
        };
    }


    // ===== The carrier's own codecs =====
    // Direct anonymous implementations rather than {@link #of(ValueEncoder, ValueDecoder)}: of() wraps
    // two lambdas, so every encode/decode would be a second, interface-dispatched call on top of the
    // codec's own. These are the leaf codecs of every persisted field, so they are written the way the
    // Data-era constants were — one object, one virtual call — and named the way {@link ByteBufCodecs}
    // names its own, with no {@code _CODEC} suffix.
    //
    // Each writes exactly the bytes the retired Data type system wrote, so a save from before the
    // migration reads
    // through them unchanged. Reads go through the boxed getters ({@code getIntBoxed} and friends):
    // the codec's type parameter is the wrapper, so the carrier hands the wrapper over without
    // unboxing it only to box it again.

    /**
     * # A boolean, stored as the byte {@code 0}/{@code 1} a boolean has always been on the wire.
     */
    ValueCodec<Boolean> BOOLEAN = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Boolean value) {
            return ops.createBooleanBoxed(value);
        }

        @Override
        public Boolean decode(ValueOps ops, Object data) {
            return ops.getBooleanBoxed(data);
        }
    };

    ValueCodec<Byte> BYTE = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Byte value) {
            return ops.createByteBoxed(value);
        }

        @Override
        public Byte decode(ValueOps ops, Object data) {
            return ops.getByteBoxed(data);
        }
    };

    ValueCodec<Short> SHORT = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Short value) {
            return ops.createShortBoxed(value);
        }

        @Override
        public Short decode(ValueOps ops, Object data) {
            return ops.getShortBoxed(data);
        }
    };

    ValueCodec<Character> CHAR = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Character value) {
            return ops.createCharBoxed(value);
        }

        @Override
        public Character decode(ValueOps ops, Object data) {
            return ops.getCharBoxed(data);
        }
    };

    ValueCodec<Integer> INT = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Integer value) {
            return ops.createIntBoxed(value);
        }

        @Override
        public Integer decode(ValueOps ops, Object data) {
            return ops.getIntBoxed(data);
        }
    };

    ValueCodec<Long> LONG = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Long value) {
            return ops.createLongBoxed(value);
        }

        @Override
        public Long decode(ValueOps ops, Object data) {
            return ops.getLongBoxed(data);
        }
    };

    ValueCodec<Float> FLOAT = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Float value) {
            return ops.createFloatBoxed(value);
        }

        @Override
        public Float decode(ValueOps ops, Object data) {
            return ops.getFloatBoxed(data);
        }
    };

    ValueCodec<Double> DOUBLE = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Double value) {
            return ops.createDoubleBoxed(value);
        }

        @Override
        public Double decode(ValueOps ops, Object data) {
            return ops.getDoubleBoxed(data);
        }
    };

    ValueCodec<String> STRING = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, String value) {
            return ops.createString(value);
        }

        @Override
        public String decode(ValueOps ops, Object data) {
            return ops.getString(data);
        }
    };

    /**
     * # A big integer as the byte array it has always been stored as.
     */
    ValueCodec<BigInteger> BIG_INTEGER = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, BigInteger value) {
            return ops.createBigInteger(value);
        }

        @Override
        public BigInteger decode(ValueOps ops, Object data) {
            return ops.getBigInteger(data);
        }
    };

    /**
     * # A UUID as its two longs.
     */
    ValueCodec<UUID> UUID = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, UUID value) {
            return ops.createUUID(value);
        }

        @Override
        public UUID decode(ValueOps ops, Object data) {
            return ops.getUUID(data);
        }
    };

    ValueCodec<boolean[]> BOOLEAN_ARRAY = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, boolean[] value) {
            return value.length == 0 ? ops.createNull() : ops.createBooleanArray(value);
        }

        @Override
        public boolean[] decode(ValueOps ops, Object data) {
            return ops.getBooleanArray(data);
        }
    };

    ValueCodec<byte[]> BYTE_ARRAY = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, byte[] value) {
            return value.length == 0 ? ops.createNull() : ops.createByteArray(value);
        }

        @Override
        public byte[] decode(ValueOps ops, Object data) {
            return ops.getByteArray(data);
        }
    };

    ValueCodec<short[]> SHORT_ARRAY = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, short[] value) {
            return value.length == 0 ? ops.createNull() : ops.createShortArray(value);
        }

        @Override
        public short[] decode(ValueOps ops, Object data) {
            return ops.getShortArray(data);
        }
    };

    ValueCodec<char[]> CHAR_ARRAY = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, char[] value) {
            return value.length == 0 ? ops.createNull() : ops.createCharArray(value);
        }

        @Override
        public char[] decode(ValueOps ops, Object data) {
            return ops.getCharArray(data);
        }
    };

    ValueCodec<int[]> INT_ARRAY = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, int[] value) {
            return value.length == 0 ? ops.createNull() : ops.createIntArray(value);
        }

        @Override
        public int[] decode(ValueOps ops, Object data) {
            return ops.getIntArray(data);
        }
    };

    ValueCodec<long[]> LONG_ARRAY = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, long[] value) {
            return value.length == 0 ? ops.createNull() : ops.createLongArray(value);
        }

        @Override
        public long[] decode(ValueOps ops, Object data) {
            return ops.getLongArray(data);
        }
    };

    ValueCodec<float[]> FLOAT_ARRAY = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, float[] value) {
            return value.length == 0 ? ops.createNull() : ops.createFloatArray(value);
        }

        @Override
        public float[] decode(ValueOps ops, Object data) {
            return ops.getFloatArray(data);
        }
    };

    ValueCodec<double[]> DOUBLE_ARRAY = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, double[] value) {
            return value.length == 0 ? ops.createNull() : ops.createDoubleArray(value);
        }

        @Override
        public double[] decode(ValueOps ops, Object data) {
            return ops.getDoubleArray(data);
        }
    };

    /**
     * # A FastUtil {@link IntList} as the {@code int[]} payload of {@link #INT_ARRAY}: nothing boxes, and an empty list is an empty array.
     */
    ValueCodec<IntList> INT_LIST = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, IntList value) {
            return ops.createIntArray(value.toIntArray());
        }

        @Override
        public IntList decode(ValueOps ops, Object data) {
            return new IntArrayList(ops.getIntArray(data));
        }
    };

    /**
     * # A FastUtil {@link IntSet} as the same {@code int[]} payload, read back into an {@link IntOpenHashSet}.
     */
    ValueCodec<IntSet> INT_SET = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, IntSet value) {
            return ops.createIntArray(value.toIntArray());
        }

        @Override
        public IntSet decode(ValueOps ops, Object data) {
            return new IntOpenHashSet(ops.getIntArray(data));
        }
    };

    /**
     * # A FastUtil {@link LongList} as the {@code long[]} payload of {@link #LONG_ARRAY}.
     */
    ValueCodec<LongList> LONG_LIST = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, LongList value) {
            return ops.createLongArray(value.toLongArray());
        }

        @Override
        public LongList decode(ValueOps ops, Object data) {
            return new LongArrayList(ops.getLongArray(data));
        }
    };

    /**
     * # A FastUtil {@link LongSet} as the same {@code long[]} payload.
     */
    ValueCodec<LongSet> LONG_SET = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, LongSet value) {
            return ops.createLongArray(value.toLongArray());
        }

        @Override
        public LongSet decode(ValueOps ops, Object data) {
            return new LongOpenHashSet(ops.getLongArray(data));
        }
    };

    /**
     * Adapts this codec through a pair of converter functions, in the instance spirit of
     * {@link StreamCodec#convert(Function, Function)}.
     *
     * @param to   {@code U -> T}, applied before encoding
     * @param from {@code T -> U}, applied after decoding
     */
    default <U> ValueCodec<U> convert(Function<? super U, ? extends T> to, Function<? super T, ? extends U> from) {
        var codec = this;
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, U value) {
                return codec.encode(ops, to.apply(value));
            }

            @Override
            public U decode(ValueOps ops, Object data) {
                return from.apply(codec.decode(ops, data));
            }
        };
    }

    /**
     * This codec made nullable on the carrier side, the same way the rest of the library marks an
     * absent value: {@code null} is stored as {@link ValueOps#createNull()} and reads back as
     * {@code null}, anything else is stored by this codec.
     */
    default ValueCodec<T> optional() {
        var codec = this;
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return ops.isNull(value) ? ops.createNull() : codec.encode(ops, value);
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return ops.isNull(data) ? null : codec.decode(ops, data);
            }
        };
    }

    /**
     * This codec made nullable with a default: a {@code null} value, or one that
     * {@link Objects#equals(Object, Object) equals} {@code defaultValue}, is stored as
     * {@link ValueOps#createNull()} — so a value at its default costs one byte instead of a full
     * payload, which is what the persisted form of such a field has always looked like — and reads
     * back as the default.
     *
     * @param defaultValue the value that is stored as absent; a {@code null} default degrades to
     *                     plain {@link #optional()}
     */
    default ValueCodec<T> optional(T defaultValue) {
        if (defaultValue == null) return optional();
        var codec = this;
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return ops.isNull(value) || defaultValue.equals(value) ? ops.createNull() : codec.encode(ops, value);
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return ops.isNull(data) ? defaultValue : codec.decode(ops, data);
            }
        };
    }

    /**
     * {@link #optional(Object)} for a default that is not a constant (a fresh collection, a value
     * read from config, …). The supplier is consulted once per encode (for the equality check) and
     * once per decode of an absent value, so it should be side-effect free; a fresh mutable default
     * is re-created on every decode.
     *
     * @param defaultSupplier supplies the value that is stored as absent
     */
    default ValueCodec<T> optional(Supplier<? extends T> defaultSupplier) {
        var codec = this;
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return ops.isNull(value) || Objects.equals(value, defaultSupplier.get())
                        ? ops.createNull()
                        : codec.encode(ops, value);
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return ops.isNull(data) ? defaultSupplier.get() : codec.decode(ops, data);
            }
        };
    }

    /**
     * A {@link List} of this codec's values, built with {@link ValueOps#createList(List)} and read
     * back with {@link ValueOps#getList(Object)} — the container the ops models directly, and the
     * shape to copy for a map once one is needed.
     */
    default ValueCodec<List<T>> list() {
        var codec = this;
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, List<T> value) {
                if (value.isEmpty()) return ops.createNull();
                var elements = new ArrayList<>(value.size());
                for (var element : value) {
                    elements.add(codec.encode(ops, element));
                }
                return ops.createList(elements);
            }

            @Override
            public List<T> decode(ValueOps ops, Object data) {
                // the absent marker an empty list is stored as, or anything that is not a list
                if (ops.isNull(data)) return new ArrayList<>();
                var elements = ops.getList(data);
                var list = new ArrayList<T>(elements.size());
                for (var element : elements) {
                    list.add(ops.isNull(element) ? null : codec.decode(ops, element));
                }
                return list;
            }
        };
    }

    /**
     * Reads one element of a container or of a composite tuple: an absent slot (the carrier's null
     * value — or a Java {@code null} a hand-built payload may hold) decodes to {@code null} rather
     * than being handed to {@code codec}, which never sees a value it was not written for.
     */
    private static <T> T element(ValueOps ops, ValueCodec<T> codec, Object data) {
        return ops.isNull(data) ? null : codec.decode(ops, data);
    }
    // ===== Tuple composites =====
    // Each one keeps the layout the retired type system wrote - a list of the component payloads, in
    // declaration order - so a value written back then still decodes through these.

    /**
     * A codec for {@code T} from 1 field, on the carrier this codec runs on.
     */
    static <T, F1> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            Function<? super F1, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Collections.singletonList(codec1.encode(ops, getter1.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(element(ops, codec1, list.getFirst()));
            }
        };
    }

    /**
     * A codec for {@code T} from 2 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            BiFunction<? super F1, ? super F2, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)));
            }
        };
    }

    /**
     * A codec for {@code T} from 3 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            Function3<? super F1, ? super F2, ? super F3, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)));
            }
        };
    }

    /**
     * A codec for {@code T} from 4 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            Function4<? super F1, ? super F2, ? super F3, ? super F4, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)));
            }
        };
    }

    /**
     * A codec for {@code T} from 5 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            Function5<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)));
            }
        };
    }

    /**
     * A codec for {@code T} from 6 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            Function6<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)));
            }
        };
    }

    /**
     * A codec for {@code T} from 7 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ValueCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            Function7<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj)),
                        codec7.encode(ops, getter7.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)),
                        element(ops, codec7, list.get(6)));
            }
        };
    }

    /**
     * A codec for {@code T} from 8 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ValueCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ValueCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            Function8<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj)),
                        codec7.encode(ops, getter7.apply(obj)),
                        codec8.encode(ops, getter8.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)),
                        element(ops, codec7, list.get(6)),
                        element(ops, codec8, list.get(7)));
            }
        };
    }

    /**
     * A codec for {@code T} from 9 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ValueCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ValueCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ValueCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            Function9<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj)),
                        codec7.encode(ops, getter7.apply(obj)),
                        codec8.encode(ops, getter8.apply(obj)),
                        codec9.encode(ops, getter9.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)),
                        element(ops, codec7, list.get(6)),
                        element(ops, codec8, list.get(7)),
                        element(ops, codec9, list.get(8)));
            }
        };
    }

    /**
     * A codec for {@code T} from 10 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ValueCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ValueCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ValueCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ValueCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            Function10<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj)),
                        codec7.encode(ops, getter7.apply(obj)),
                        codec8.encode(ops, getter8.apply(obj)),
                        codec9.encode(ops, getter9.apply(obj)),
                        codec10.encode(ops, getter10.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)),
                        element(ops, codec7, list.get(6)),
                        element(ops, codec8, list.get(7)),
                        element(ops, codec9, list.get(8)),
                        element(ops, codec10, list.get(9)));
            }
        };
    }

    /**
     * A codec for {@code T} from 11 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ValueCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ValueCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ValueCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ValueCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ValueCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            Function11<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj)),
                        codec7.encode(ops, getter7.apply(obj)),
                        codec8.encode(ops, getter8.apply(obj)),
                        codec9.encode(ops, getter9.apply(obj)),
                        codec10.encode(ops, getter10.apply(obj)),
                        codec11.encode(ops, getter11.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)),
                        element(ops, codec7, list.get(6)),
                        element(ops, codec8, list.get(7)),
                        element(ops, codec9, list.get(8)),
                        element(ops, codec10, list.get(9)),
                        element(ops, codec11, list.get(10)));
            }
        };
    }

    /**
     * A codec for {@code T} from 12 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ValueCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ValueCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ValueCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ValueCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ValueCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            ValueCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            Function12<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj)),
                        codec7.encode(ops, getter7.apply(obj)),
                        codec8.encode(ops, getter8.apply(obj)),
                        codec9.encode(ops, getter9.apply(obj)),
                        codec10.encode(ops, getter10.apply(obj)),
                        codec11.encode(ops, getter11.apply(obj)),
                        codec12.encode(ops, getter12.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)),
                        element(ops, codec7, list.get(6)),
                        element(ops, codec8, list.get(7)),
                        element(ops, codec9, list.get(8)),
                        element(ops, codec10, list.get(9)),
                        element(ops, codec11, list.get(10)),
                        element(ops, codec12, list.get(11)));
            }
        };
    }

    /**
     * A codec for {@code T} from 13 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ValueCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ValueCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ValueCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ValueCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ValueCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            ValueCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            ValueCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            Function13<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj)),
                        codec7.encode(ops, getter7.apply(obj)),
                        codec8.encode(ops, getter8.apply(obj)),
                        codec9.encode(ops, getter9.apply(obj)),
                        codec10.encode(ops, getter10.apply(obj)),
                        codec11.encode(ops, getter11.apply(obj)),
                        codec12.encode(ops, getter12.apply(obj)),
                        codec13.encode(ops, getter13.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)),
                        element(ops, codec7, list.get(6)),
                        element(ops, codec8, list.get(7)),
                        element(ops, codec9, list.get(8)),
                        element(ops, codec10, list.get(9)),
                        element(ops, codec11, list.get(10)),
                        element(ops, codec12, list.get(11)),
                        element(ops, codec13, list.get(12)));
            }
        };
    }

    /**
     * A codec for {@code T} from 14 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ValueCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ValueCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ValueCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ValueCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ValueCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            ValueCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            ValueCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            ValueCodec<F14> codec14, Function<? super T, ? extends F14> getter14,
            Function14<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj)),
                        codec7.encode(ops, getter7.apply(obj)),
                        codec8.encode(ops, getter8.apply(obj)),
                        codec9.encode(ops, getter9.apply(obj)),
                        codec10.encode(ops, getter10.apply(obj)),
                        codec11.encode(ops, getter11.apply(obj)),
                        codec12.encode(ops, getter12.apply(obj)),
                        codec13.encode(ops, getter13.apply(obj)),
                        codec14.encode(ops, getter14.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)),
                        element(ops, codec7, list.get(6)),
                        element(ops, codec8, list.get(7)),
                        element(ops, codec9, list.get(8)),
                        element(ops, codec10, list.get(9)),
                        element(ops, codec11, list.get(10)),
                        element(ops, codec12, list.get(11)),
                        element(ops, codec13, list.get(12)),
                        element(ops, codec14, list.get(13)));
            }
        };
    }

    /**
     * A codec for {@code T} from 15 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ValueCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ValueCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ValueCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ValueCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ValueCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            ValueCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            ValueCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            ValueCodec<F14> codec14, Function<? super T, ? extends F14> getter14,
            ValueCodec<F15> codec15, Function<? super T, ? extends F15> getter15,
            Function15<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj)),
                        codec7.encode(ops, getter7.apply(obj)),
                        codec8.encode(ops, getter8.apply(obj)),
                        codec9.encode(ops, getter9.apply(obj)),
                        codec10.encode(ops, getter10.apply(obj)),
                        codec11.encode(ops, getter11.apply(obj)),
                        codec12.encode(ops, getter12.apply(obj)),
                        codec13.encode(ops, getter13.apply(obj)),
                        codec14.encode(ops, getter14.apply(obj)),
                        codec15.encode(ops, getter15.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)),
                        element(ops, codec7, list.get(6)),
                        element(ops, codec8, list.get(7)),
                        element(ops, codec9, list.get(8)),
                        element(ops, codec10, list.get(9)),
                        element(ops, codec11, list.get(10)),
                        element(ops, codec12, list.get(11)),
                        element(ops, codec13, list.get(12)),
                        element(ops, codec14, list.get(13)),
                        element(ops, codec15, list.get(14)));
            }
        };
    }

    /**
     * A codec for {@code T} from 16 fields, on the carrier this codec runs on.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15, F16> ValueCodec<T> composite(
            ValueCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ValueCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ValueCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ValueCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ValueCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ValueCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ValueCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ValueCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ValueCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ValueCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ValueCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            ValueCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            ValueCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            ValueCodec<F14> codec14, Function<? super T, ? extends F14> getter14,
            ValueCodec<F15> codec15, Function<? super T, ? extends F15> getter15,
            ValueCodec<F16> codec16, Function<? super T, ? extends F16> getter16,
            Function16<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? super F16, ? extends T> constructor) {
        return new ValueCodec<>() {
            @Override
            public Object encode(ValueOps ops, T obj) {
                return ops.createList(Arrays.asList(
                        codec1.encode(ops, getter1.apply(obj)),
                        codec2.encode(ops, getter2.apply(obj)),
                        codec3.encode(ops, getter3.apply(obj)),
                        codec4.encode(ops, getter4.apply(obj)),
                        codec5.encode(ops, getter5.apply(obj)),
                        codec6.encode(ops, getter6.apply(obj)),
                        codec7.encode(ops, getter7.apply(obj)),
                        codec8.encode(ops, getter8.apply(obj)),
                        codec9.encode(ops, getter9.apply(obj)),
                        codec10.encode(ops, getter10.apply(obj)),
                        codec11.encode(ops, getter11.apply(obj)),
                        codec12.encode(ops, getter12.apply(obj)),
                        codec13.encode(ops, getter13.apply(obj)),
                        codec14.encode(ops, getter14.apply(obj)),
                        codec15.encode(ops, getter15.apply(obj)),
                        codec16.encode(ops, getter16.apply(obj))));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var list = ops.getList(data);
                return constructor.apply(
                        element(ops, codec1, list.get(0)),
                        element(ops, codec2, list.get(1)),
                        element(ops, codec3, list.get(2)),
                        element(ops, codec4, list.get(3)),
                        element(ops, codec5, list.get(4)),
                        element(ops, codec6, list.get(5)),
                        element(ops, codec7, list.get(6)),
                        element(ops, codec8, list.get(7)),
                        element(ops, codec9, list.get(8)),
                        element(ops, codec10, list.get(9)),
                        element(ops, codec11, list.get(10)),
                        element(ops, codec12, list.get(11)),
                        element(ops, codec13, list.get(12)),
                        element(ops, codec14, list.get(13)),
                        element(ops, codec15, list.get(14)),
                        element(ops, codec16, list.get(15)));
            }
        };
    }

    // ===== The remaining composition helpers, ported from the Data side =====

    /**
     * A codec that writes nothing and always decodes {@code value} — for a constant, a version marker
     * or a field that never changes.
     */
    static <T> ValueCodec<T> unit(T value) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T ignored) {
                return ops.createNull();
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return value;
            }
        };
    }

    /**
     * A map as the list of its key/value pairs, in iteration order — the layout the Data side wrote,
     * so a map stored before the migration reads back here. A payload that holds fewer than two
     * entries decodes to {@code factory.apply(1)}, an empty map, rather than to {@code null}.
     *
     * @param factory creates the map by expected size
     */
    static <K, U, M extends Map<K, U>> ValueCodec<M> map(IntFunction<M> factory, ValueCodec<K> keyCodec, ValueCodec<U> valueCodec) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, M obj) {
                if (obj.isEmpty()) return ops.createNull();
                var pairs = new ArrayList<>(obj.size() * 2);
                obj.forEach((key, value) -> {
                    pairs.add(keyCodec.encode(ops, key));
                    pairs.add(valueCodec.encode(ops, value));
                });
                return ops.createList(pairs);
            }

            @Override
            public M decode(ValueOps ops, Object data) {
                // the absent marker an empty map is stored as, or anything that is not a list
                if (ops.isNull(data)) return factory.apply(1);
                var pairs = ops.getList(data);
                if (pairs.size() < 2) return factory.apply(1);
                var map = factory.apply(pairs.size() / 2);
                for (int i = 0; i + 1 < pairs.size(); i += 2) {
                    map.put(element(ops, keyCodec, pairs.get(i)), element(ops, valueCodec, pairs.get(i + 1)));
                }
                return map;
            }
        };
    }

    /**
     * A collection as the list of its elements, in iteration order — the Data layout. An empty payload
     * decodes to {@code factory.apply(1)} rather than to {@code null}.
     *
     * @param factory creates the collection by expected size
     */
    static <E, C extends Collection<E>> ValueCodec<C> collection(IntFunction<C> factory, ValueCodec<E> elementCodec) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, C obj) {
                if (obj.isEmpty()) return ops.createNull();
                var elements = new ArrayList<>(obj.size());
                for (var element : obj) {
                    elements.add(elementCodec.encode(ops, element));
                }
                return ops.createList(elements);
            }

            @Override
            public C decode(ValueOps ops, Object data) {
                // the absent marker an empty collection is stored as, or anything that is not a list
                if (ops.isNull(data)) return factory.apply(1);
                var elements = ops.getList(data);
                var collection = factory.apply(elements.size());
                for (var element : elements) {
                    collection.add(ops.isNull(element) ? null : elementCodec.decode(ops, element));
                }
                return collection;
            }
        };
    }

    /**
     * An object array as the list of its elements. A <em>primitive</em> array has its own codec and
     * does not need this; the array is allocated from the payload's length. A zero-length array is
     * stored as the absent marker and a payload that is not a list decodes back to a zero-length
     * array, while a null-valued element decodes to a {@code null} slot instead of reaching
     * {@code elementCodec}.
     */
    static <E> ValueCodec<E[]> array(Class<E> type, ValueCodec<E> elementCodec) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, E[] obj) {
                if (obj.length == 0) return ops.createNull();
                var elements = new ArrayList<>(obj.length);
                for (var element : obj) {
                    elements.add(elementCodec.encode(ops, element));
                }
                return ops.createList(elements);
            }

            @SuppressWarnings("unchecked")
            @Override
            public E[] decode(ValueOps ops, Object data) {
                // the absent marker an empty array is stored as, or anything that is not a list
                if (ops.isNull(data)) return (E[]) Array.newInstance(type, 0);
                var elements = ops.getList(data);
                var array = (E[]) Array.newInstance(type, elements.size());
                for (int i = 0; i < elements.size(); i++) {
                    array[i] = element(ops, elementCodec, elements.get(i));
                }
                return array;
            }
        };
    }

    /**
     * Writes a discriminator with its own codec, then the payload with the codec that discriminator
     * selects — a list of two elements, the Data layout.
     */
    static <T, D> ValueCodec<T> dispatch(
            ValueCodec<D> discriminatorCodec, Function<? super T, ? extends D> discriminator,
            Function<? super D, ? extends ValueCodec<? extends T>> codecGetter) {
        return new ValueCodec<>() {

            @SuppressWarnings("unchecked")
            @Override
            public Object encode(ValueOps ops, T obj) {
                D type = discriminator.apply(obj);
                var payload = (ValueCodec<T>) codecGetter.apply(type);
                return ops.createList(Arrays.asList(discriminatorCodec.encode(ops, type), payload.encode(ops, obj)));
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var pair = ops.getList(data);
                D type = discriminatorCodec.decode(ops, pair.get(0));
                return codecGetter.apply(type).decode(ops, pair.get(1));
            }
        };
    }

    /**
     * A codec that can reference itself, for trees and linked structures. The {@code wrapped} function
     * receives a placeholder that delegates to the resolved codec.
     */
    static <T> ValueCodec<T> recursive(Function<ValueCodec<T>, ValueCodec<T>> wrapped) {
        return new ValueCodec<>() {
            private ValueCodec<T> resolved;

            private ValueCodec<T> resolve() {
                if (resolved == null) resolved = wrapped.apply(this);
                return resolved;
            }

            @Override
            public Object encode(ValueOps ops, T obj) {
                return resolve().encode(ops, obj);
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return resolve().decode(ops, data);
            }
        };
    }


    /**
     * A {@link StreamCodec} seen as a value codec: the wire bytes are stored as one
     * {@code BYTE_ARRAY} value, which is what the retired {@code Data} bridge used to do. Use it
     * for a quick persistence path; a type that is persisted often wants a codec of its own.
     */
    static <T> ValueCodec<T> fromStreamCodec(StreamCodec<? super FriendlyByteBuf, T> streamCodec) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                var buf = Unpooled.buffer();
                try {
                    streamCodec.encode(new FriendlyByteBuf(buf), value);
                    var bytes = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), bytes);
                    return ops.createByteArray(bytes);
                } finally {
                    buf.release();
                }
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var buf = Unpooled.wrappedBuffer(ops.getByteArray(data));
                try {
                    return streamCodec.decode(new FriendlyByteBuf(buf));
                } finally {
                    buf.release();
                }
            }
        };
    }

    static <T> ValueCodec<T> custom(CustomTypes.Type<T> type) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return ops.createCustom(type, value);
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return ops.getCustom(type, data);
            }
        };
    }
}