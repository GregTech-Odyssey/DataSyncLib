package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.datastream.data.*;
import com.mojang.datafixers.util.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Combined encoder/decoder interface for {@link com.gto.datasynclib.datastream.data.Data}-based
 * persistent storage.
 *
 * <p>Extends both {@link DataDecoder} and {@link DataEncoder} for bidirectional serialization.
 * Contains built-in codec constants for all Java primitives, arrays, String, UUID, and BigInteger.
 * There is no per-interface registry: runtime type lookup goes through
 * {@link com.gto.datasynclib.DataSyncCodec#get(Class)}, the single table for both paths, and
 * {@link #get(Class)} hands back this path's own half of the result.</p>
 *
 * <p>Factory methods {@link #of} adapt from {@link ByteStreamCodec}, Mojang {@link com.mojang.serialization.Codec},
 * or custom encoder/decoder pairs. {@link #convert} adapts an existing codec with converter
 * functions, while {@link #map} / {@link #collection} / {@link #array} build container codecs.</p>
 *
 * <p>Every builder that takes a codec also has an instance form on the codec itself, with the
 * receiver replacing that codec argument and the remaining parameters in the static order —
 * {@code STRING_CODEC.optional()}, {@code STRING_CODEC.collection(ArrayList::new)},
 * {@code STRING_CODEC.asKey(HashMap::new, INT_CODEC)}, {@code STRING_CODEC.toStreamCodec()}, … .
 * {@link #optional} additionally treats a value equal to a default as absent: it is stored the same
 * way as {@code null} ({@link NullData#INSTANCE} on this path) and decodes back to the default; the
 * {@link Supplier} overload and its lambda caveat are documented on the method.</p>
 *
 * <h3>Performance: the helper paths box</h3>
 * <p>Every helper below is generic over the payload type, so its components are <em>boxed</em>:
 * {@link #convert} adapts through generic converter functions, and {@link #map} /
 * {@link #collection} iterate the container with boxed keys/values and write the data side as a
 * {@code ListData} tuple. That is the right choice for object payloads and for {@code java.util}
 * containers (whose elements are boxed by definition), but it is pure overhead for a type that is
 * a few primitives and is synchronized often — write that pair by hand and use the scalar
 * {@code Data} types directly, the way {@link com.gto.datasynclib.util.DataCodecs#VEC3I_CODEC}
 * does. Primitive <em>arrays</em> are already covered by primitive-backed codecs
 * ({@code BOOLEANS_CODEC}, {@code INTS_CODEC}, {@code LONGS_CODEC}, …), so {@link #array} is only
 * needed for object arrays. {@link #of(ByteStreamCodec)} additionally round-trips through an
 * intermediate buffer, which is convenient but not free either.</p>
 *
 * @param <T> the type this codec can encode and decode
 */
public interface DataCodec<T> extends DataEncoder<T>, DataDecoder<T> {

    /**
     * Lifts this codec to a Mojang DFU {@link Codec} that transports the Data payload as a byte
     * list, so it can be used wherever a DFU codec is expected.
     *
     * @param dataVersion version handed to {@link DataDecoder#decode(Data, int)} on the way back
     *                    in; it is not recorded in the encoded form
     */
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

    /**
     * Adapts a network codec: the stream bytes are carried inside a
     * {@link com.gto.datasynclib.datastream.data.ByteArrayData}, so the disk form mirrors the
     * wire form at the cost of an extra buffer round-trip.
     */
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

    /**
     * Adapts a Mojang DFU {@link Codec} through {@link DataOps#INSTANCE}.
     *
     * <p>Failures surface as thrown {@link java.util.NoSuchElementException} /
     * {@code JsonParseException} from the {@code orElseThrow()} calls rather than as a
     * {@code DataResult} error, so this adapter is only safe for trusted data.</p>
     */
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

    /**
     * Pairs an independent encoder and decoder into a codec.
     */
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

    /**
     * Adapts a codec of another type through a pair of converter functions.
     *
     * <p>Both directions pass through the generic value, so a primitive-based {@code V}/{@code K}
     * is boxed twice per round-trip; see the class documentation for when to hand-write instead.</p>
     *
     * @param codec           codec of the stored type {@code K}
     * @param encodeConverter {@code V -> K}, applied before encoding
     * @param decodeConverter {@code K -> V}, applied after decoding
     */
    static <K, V> DataCodec<V> convert(DataCodec<K> codec, Function<? super V, ? extends K> encodeConverter, Function<? super K, ? extends V> decodeConverter) {
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

    /**
     * Map codec: encodes as a flat {@link ListData} of alternating keys and values.
     *
     * <p>Decoding requires a {@code ListData} with more than one entry; anything else (including
     * a {@code null} marker or an empty list) yields {@code function.apply(1)} — an empty map —
     * rather than {@code null}. Note that a truncated payload is read positionally without a
     * bounds guard, and that keys/values travel as boxed generic types.</p>
     *
     * @param function factory that creates the map by expected size (e.g. {@code HashMap::new})
     */
    static <K, V, M extends Map<K, V>> DataCodec<M> map(IntFunction<M> function, DataCodec<K> keyCodec, DataCodec<V> valueCodec) {
        return new DataCodec<>() {

            @Override
            public @NotNull Data encode(M obj) {
                var data = new ListData();
                obj.forEach((k, v) -> {
                    data.add(keyCodec.encode(k));
                    data.add(valueCodec.encode(v));
                });
                return data;
            }

            @Override
            public M decode(@NotNull Data data, int dataVersion) {
                if (data instanceof ListData(List<Data> list) && list.size() > 1) {
                    var map = function.apply(list.size() / 2);
                    for (int i = 0; i < list.size(); i++) {
                        map.put(keyCodec.decode(list.get(i++), dataVersion), valueCodec.decode(list.get(i), dataVersion));
                    }
                    return map;
                }
                return function.apply(1);
            }
        };
    }

    /**
     * Collection codec: encodes as a {@link ListData} with no length prefix.
     *
     * <p>A non-list or empty payload decodes to {@code function.apply(1)} — an empty collection —
     * rather than {@code null}. Elements travel as boxed generic types.</p>
     *
     * @param function factory that creates the collection by expected size (e.g. {@code ArrayList::new})
     */
    static <T, C extends Collection<T>> DataCodec<C> collection(IntFunction<C> function, DataCodec<T> codec) {
        return new DataCodec<>() {

            @Override
            public @NotNull Data encode(C obj) {
                var data = new ListData();
                obj.forEach(o -> data.add(codec.encode(o)));
                return data;
            }

            @Override
            public C decode(@NotNull Data data, int dataVersion) {
                if (data instanceof ListData(List<Data> list) && !list.isEmpty()) {
                    var array = function.apply(list.size());
                    list.forEach(d -> array.add(codec.decode(d, dataVersion)));
                    return array;
                }
                return function.apply(1);
            }
        };
    }

    /**
     * Object-array codec: encodes as a {@link ListData}.
     *
     * <p>A non-list or empty payload decodes to a zero-length array of {@code type}
     * ({@code Array.newInstance(type, 0)}); elements travel as boxed generic types. A
     * <em>primitive</em> array has its own primitive-backed codec
     * ({@code BOOLEANS_CODEC}, {@code INTS_CODEC}, {@code LONGS_CODEC}, …) and does not need this.</p>
     */
    static <T> DataCodec<T[]> array(Class<T> type, DataCodec<T> codec) {
        return new DataCodec<>() {

            @Override
            public @NotNull Data encode(T[] obj) {
                var data = new ListData();
                for (var o : obj) {
                    data.add(codec.encode(o));
                }
                return data;
            }

            @Override
            public T[] decode(@NotNull Data data, int dataVersion) {
                if (data instanceof ListData(List<Data> list) && !list.isEmpty()) {
                    var size = list.size();
                    var array = (T[]) Array.newInstance(type, size);
                    for (int i = 0; i < size; i++) {
                        array[i] = codec.decode(list.get(i), dataVersion);
                    }
                    return array;
                }
                return (T[]) Array.newInstance(type, 0);
            }
        };
    }

    // ===== Optional adapters =====

    /**
     * This codec made nullable: {@code null} is stored as {@link NullData#INSTANCE} and decodes
     * back to {@code null}, while any other value is stored as this codec's own payload.
     *
     * <p>No extra marker is needed on this path: the Data type system already tags every payload
     * with a type id, so an absent value costs one byte (the null tag) and a present one costs
     * exactly what this codec writes. The network path has no such tag, so
     * {@link ByteStreamCodec#optional()} spends a boolean instead; a {@link CombinedCodec}
     * overrides this method to wrap both paths at once.</p>
     *
     * <p>Equivalent to {@code DataCodec.optional(this)}.</p>
     */
    default DataCodec<T> optional() {
        return DataCodec.optional(this);
    }

    /**
     * This codec with a default: a {@code null} value, or one that
     * {@link Objects#equals(Object, Object) equals} {@code defaultValue}, is stored as
     * {@link NullData#INSTANCE} — the same absent form as {@link #optional()} — and decodes back to
     * the default instead of {@code null}. Any other value is stored as this codec's payload.
     *
     * <p>Equivalent to {@code DataCodec.optional(this, defaultValue)}.</p>
     *
     * @param defaultValue the value that is stored as absent; a {@code null} default degrades to
     *                     plain {@link #optional()}
     */
    default DataCodec<T> optional(T defaultValue) {
        return DataCodec.optional(this, defaultValue);
    }

    /**
     * This codec with a default produced by {@code defaultSupplier} — the supplier form of
     * {@link #optional(Object)}, for a default that is not a constant (a fresh collection, a value
     * read from config, …).
     *
     * <p>Because {@code T} is already fixed by this codec, a bare lambda or method reference is
     * ambiguous between {@link #optional(Object)} and this overload
     * ({@code codec.optional(ArrayList::new)} does not compile) — pass a typed
     * {@code Supplier<T>} variable, or cast the lambda to {@code Supplier<T>}.</p>
     *
     * <p>The supplier is consulted once per encode (for the equality check) and once per decode of
     * an absent payload, so it should be side-effect free and cheap; a fresh mutable default is
     * re-created on every decode, which is what you want for collections.</p>
     *
     * <p>Equivalent to {@code DataCodec.optional(this, defaultSupplier)}.</p>
     *
     * @param defaultSupplier supplies the value that is stored as absent
     */
    default DataCodec<T> optional(Supplier<? extends T> defaultSupplier) {
        return DataCodec.optional(this, defaultSupplier);
    }

    /**
     * {@link #optional()}, as a builder over an arbitrary codec.
     *
     * @param codec codec for the present (non-{@code null}) value
     * @param <T>   the value type
     */
    static <T> DataCodec<T> optional(DataCodec<T> codec) {
        return new DataCodec<>() {

            @Override
            public @NotNull Data encode(T obj) {
                return obj == null ? NullData.INSTANCE : codec.encode(obj);
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                return data.isNull() ? null : codec.decode(data, dataVersion);
            }
        };
    }

    /**
     * {@link #optional(Object)}, as a builder over an arbitrary codec.
     *
     * @param codec        codec for the present value
     * @param defaultValue the value that is stored as absent; a {@code null} default degrades to
     *                     plain {@link #optional(DataCodec)}
     * @param <T>          the value type
     */
    static <T> DataCodec<T> optional(DataCodec<T> codec, T defaultValue) {
        return new DataCodec<>() {

            @Override
            public @NotNull Data encode(T obj) {
                return obj == null || Objects.equals(obj, defaultValue) ? NullData.INSTANCE : codec.encode(obj);
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                return data.isNull() ? defaultValue : codec.decode(data, dataVersion);
            }
        };
    }

    /**
     * {@link #optional(Supplier)}, as a builder over an arbitrary codec.
     *
     * <p>As on the instance form, a bare lambda or method reference is ambiguous between this
     * overload and {@link #optional(DataCodec, Object)} — {@code optional(codec, ArrayList::new)}
     * does not compile — so pass a typed {@code Supplier<T>} variable or cast the lambda.</p>
     *
     * @param codec           codec for the present value
     * @param defaultSupplier supplies the value that is stored as absent
     * @param <T>             the value type
     */
    static <T> DataCodec<T> optional(DataCodec<T> codec, Supplier<? extends T> defaultSupplier) {
        return new DataCodec<>() {

            @Override
            public @NotNull Data encode(T obj) {
                return obj == null || Objects.equals(obj, defaultSupplier.get()) ? NullData.INSTANCE : codec.encode(obj);
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                return data.isNull() ? defaultSupplier.get() : codec.decode(data, dataVersion);
            }
        };
    }

    // ===== Instance builder mirrors =====
    // Every static builder that takes a codec also exists as an instance method on the codec
    // itself: the receiver replaces that codec argument and the remaining parameters keep the
    // static order and meaning. A CombinedCodec overrides the ones both halves declare, so the
    // same call works — with a DataSyncCodec result — on a codec that covers both paths.

    /**
     * Adapts this data codec to the network path — the instance form of
     * {@link ByteStreamCodec#of(DataCodec)}: the {@link Data} payload is written inline
     * ({@link Data#writeData(FriendlyByteBuf, Data)}), without the length-prefixed byte array that
     * {@link com.gto.datasynclib.DataSyncCodec#of(DataCodec)} goes through.
     */
    default ByteStreamCodec<T> toStreamCodec() {
        return ByteStreamCodec.of(this);
    }

    /**
     * Pairs this data codec with the stream codec derived from it ({@link #toStreamCodec()}) into
     * one combined codec — the instance form of {@link CombinedCodec#of(DataCodec)}.
     */
    default DataSyncCodec<T> toDataSyncCodec() {
        return DataSyncCodec.of(this);
    }

    /**
     * Adapts this codec through a pair of converter functions — the instance form of
     * {@link #convert(DataCodec, Function, Function)}, with this codec's type as the stored type
     * {@code K}.
     */
    default <V> DataCodec<V> convert(Function<? super V, ? extends T> encodeConverter, Function<? super T, ? extends V> decodeConverter) {
        return convert(this, encodeConverter, decodeConverter);
    }

    /**
     * Map codec keyed by this codec, with {@code valueCodec} for the values — the instance form of
     * {@link #map(IntFunction, DataCodec, DataCodec)}. A non-list or empty payload decodes to an
     * empty map.
     */
    default <V, M extends Map<T, V>> DataCodec<M> asKey(IntFunction<M> function, DataCodec<V> valueCodec) {
        return map(function, this, valueCodec);
    }

    /**
     * Map codec valued by this codec, with {@code keyCodec} for the keys — the instance form of
     * {@link #map(IntFunction, DataCodec, DataCodec)}, with this codec on the value side.
     */
    default <K, M extends Map<K, T>> DataCodec<M> asValue(IntFunction<M> function, DataCodec<K> keyCodec) {
        return map(function, keyCodec, this);
    }

    /**
     * Collection codec over this codec's elements — the instance form of
     * {@link #collection(IntFunction, DataCodec)}.
     */
    default <C extends Collection<T>> DataCodec<C> collection(IntFunction<C> function) {
        return collection(function, this);
    }

    /**
     * Object-array codec over this codec's elements — the instance form of
     * {@link #array(Class, DataCodec)}. A primitive array has its own codec and does not need this.
     */
    default DataCodec<T[]> array(Class<T> type) {
        return array(type, this);
    }

    /**
     * Creates a codec that writes nothing on encode and always returns
     * the given constant on decode.
     *
     * @param <T>   the value type
     * @param value the constant value to return on every decode
     * @return a codec that returns {@link NullData#INSTANCE} on encode and {@code value} on decode
     */
    static <T> DataCodec<T> unit(T value) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return NullData.INSTANCE;
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                return value;
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from a single field, using
     * {@link ListData} as the container.
     */
    static <T, F1> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            Function<? super F1, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(codec1.encode(getter1.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(codec1.decode(list.get(0), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from two fields.
     */
    static <T, F1, F2> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            BiFunction<? super F1, ? super F2, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from three fields.
     */
    static <T, F1, F2, F3> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            Function3<? super F1, ? super F2, ? super F3, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from four fields.
     */
    static <T, F1, F2, F3, F4> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            Function4<? super F1, ? super F2, ? super F3, ? super F4, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from five fields.
     */
    static <T, F1, F2, F3, F4, F5> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            Function5<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from six fields.
     */
    static <T, F1, F2, F3, F4, F5, F6> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            Function6<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion));
            }
        };
    }

    /**
     * Creates a polymorphic codec that dispatches based on a discriminator value.
     * Encoded as a {@link ListData} with two elements: the discriminator and the typed payload.
     */
    static <T, D> DataCodec<T> dispatch(
            DataCodec<D> discriminatorCodec,
            Function<? super T, ? extends D> discriminator,
            Function<? super D, ? extends DataCodec<? extends T>> codecGetter) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                D type = discriminator.apply(obj);
                DataCodec<? extends T> codec = codecGetter.apply(type);
                return ListData.of(
                        discriminatorCodec.encode(type),
                        ((DataCodec<T>) codec).encode(obj));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                D type = discriminatorCodec.decode(list.get(0), dataVersion);
                DataCodec<? extends T> codec = codecGetter.apply(type);
                return codec.decode(list.get(1), dataVersion);
            }
        };
    }

    /**
     * Creates a codec that can reference itself, enabling serialization of recursive
     * data structures.
     */
    static <T> DataCodec<T> recursive(Function<DataCodec<T>, DataCodec<T>> wrapped) {
        return new DataCodec<>() {
            private DataCodec<T> resolved;

            private DataCodec<T> resolve() {
                if (resolved == null) resolved = wrapped.apply(this);
                return resolved;
            }

            @Override
            public @NotNull Data encode(T obj) {
                return resolve().encode(obj);
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                return resolve().decode(data, dataVersion);
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from seven fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            DataCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            Function7<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)),
                        codec7.encode(getter7.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion),
                        codec7.decode(list.get(6), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from eight fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            DataCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            DataCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            Function8<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)),
                        codec7.encode(getter7.apply(obj)),
                        codec8.encode(getter8.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion),
                        codec7.decode(list.get(6), dataVersion),
                        codec8.decode(list.get(7), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from nine fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            DataCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            DataCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            DataCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            Function9<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)),
                        codec7.encode(getter7.apply(obj)),
                        codec8.encode(getter8.apply(obj)),
                        codec9.encode(getter9.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion),
                        codec7.decode(list.get(6), dataVersion),
                        codec8.decode(list.get(7), dataVersion),
                        codec9.decode(list.get(8), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from ten fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            DataCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            DataCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            DataCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            DataCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            Function10<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)),
                        codec7.encode(getter7.apply(obj)),
                        codec8.encode(getter8.apply(obj)),
                        codec9.encode(getter9.apply(obj)),
                        codec10.encode(getter10.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion),
                        codec7.decode(list.get(6), dataVersion),
                        codec8.decode(list.get(7), dataVersion),
                        codec9.decode(list.get(8), dataVersion),
                        codec10.decode(list.get(9), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from eleven fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            DataCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            DataCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            DataCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            DataCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            DataCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            Function11<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)),
                        codec7.encode(getter7.apply(obj)),
                        codec8.encode(getter8.apply(obj)),
                        codec9.encode(getter9.apply(obj)),
                        codec10.encode(getter10.apply(obj)),
                        codec11.encode(getter11.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion),
                        codec7.decode(list.get(6), dataVersion),
                        codec8.decode(list.get(7), dataVersion),
                        codec9.decode(list.get(8), dataVersion),
                        codec10.decode(list.get(9), dataVersion),
                        codec11.decode(list.get(10), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from twelve fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            DataCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            DataCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            DataCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            DataCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            DataCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            DataCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            Function12<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)),
                        codec7.encode(getter7.apply(obj)),
                        codec8.encode(getter8.apply(obj)),
                        codec9.encode(getter9.apply(obj)),
                        codec10.encode(getter10.apply(obj)),
                        codec11.encode(getter11.apply(obj)),
                        codec12.encode(getter12.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion),
                        codec7.decode(list.get(6), dataVersion),
                        codec8.decode(list.get(7), dataVersion),
                        codec9.decode(list.get(8), dataVersion),
                        codec10.decode(list.get(9), dataVersion),
                        codec11.decode(list.get(10), dataVersion),
                        codec12.decode(list.get(11), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from thirteen fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            DataCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            DataCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            DataCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            DataCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            DataCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            DataCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            DataCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            Function13<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)),
                        codec7.encode(getter7.apply(obj)),
                        codec8.encode(getter8.apply(obj)),
                        codec9.encode(getter9.apply(obj)),
                        codec10.encode(getter10.apply(obj)),
                        codec11.encode(getter11.apply(obj)),
                        codec12.encode(getter12.apply(obj)),
                        codec13.encode(getter13.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion),
                        codec7.decode(list.get(6), dataVersion),
                        codec8.decode(list.get(7), dataVersion),
                        codec9.decode(list.get(8), dataVersion),
                        codec10.decode(list.get(9), dataVersion),
                        codec11.decode(list.get(10), dataVersion),
                        codec12.decode(list.get(11), dataVersion),
                        codec13.decode(list.get(12), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from fourteen fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            DataCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            DataCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            DataCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            DataCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            DataCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            DataCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            DataCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            DataCodec<F14> codec14, Function<? super T, ? extends F14> getter14,
            Function14<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)),
                        codec7.encode(getter7.apply(obj)),
                        codec8.encode(getter8.apply(obj)),
                        codec9.encode(getter9.apply(obj)),
                        codec10.encode(getter10.apply(obj)),
                        codec11.encode(getter11.apply(obj)),
                        codec12.encode(getter12.apply(obj)),
                        codec13.encode(getter13.apply(obj)),
                        codec14.encode(getter14.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion),
                        codec7.decode(list.get(6), dataVersion),
                        codec8.decode(list.get(7), dataVersion),
                        codec9.decode(list.get(8), dataVersion),
                        codec10.decode(list.get(9), dataVersion),
                        codec11.decode(list.get(10), dataVersion),
                        codec12.decode(list.get(11), dataVersion),
                        codec13.decode(list.get(12), dataVersion),
                        codec14.decode(list.get(13), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from fifteen fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            DataCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            DataCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            DataCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            DataCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            DataCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            DataCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            DataCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            DataCodec<F14> codec14, Function<? super T, ? extends F14> getter14,
            DataCodec<F15> codec15, Function<? super T, ? extends F15> getter15,
            Function15<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)),
                        codec7.encode(getter7.apply(obj)),
                        codec8.encode(getter8.apply(obj)),
                        codec9.encode(getter9.apply(obj)),
                        codec10.encode(getter10.apply(obj)),
                        codec11.encode(getter11.apply(obj)),
                        codec12.encode(getter12.apply(obj)),
                        codec13.encode(getter13.apply(obj)),
                        codec14.encode(getter14.apply(obj)),
                        codec15.encode(getter15.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion),
                        codec7.decode(list.get(6), dataVersion),
                        codec8.decode(list.get(7), dataVersion),
                        codec9.decode(list.get(8), dataVersion),
                        codec10.decode(list.get(9), dataVersion),
                        codec11.decode(list.get(10), dataVersion),
                        codec12.decode(list.get(11), dataVersion),
                        codec13.decode(list.get(12), dataVersion),
                        codec14.decode(list.get(13), dataVersion),
                        codec15.decode(list.get(14), dataVersion));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from sixteen fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15, F16> DataCodec<T> composite(
            DataCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            DataCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            DataCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            DataCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            DataCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            DataCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            DataCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            DataCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            DataCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            DataCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            DataCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            DataCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            DataCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            DataCodec<F14> codec14, Function<? super T, ? extends F14> getter14,
            DataCodec<F15> codec15, Function<? super T, ? extends F15> getter15,
            DataCodec<F16> codec16, Function<? super T, ? extends F16> getter16,
            Function16<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? super F16, ? extends T> constructor) {
        return new DataCodec<>() {
            @Override
            public @NotNull Data encode(T obj) {
                return ListData.of(
                        codec1.encode(getter1.apply(obj)),
                        codec2.encode(getter2.apply(obj)),
                        codec3.encode(getter3.apply(obj)),
                        codec4.encode(getter4.apply(obj)),
                        codec5.encode(getter5.apply(obj)),
                        codec6.encode(getter6.apply(obj)),
                        codec7.encode(getter7.apply(obj)),
                        codec8.encode(getter8.apply(obj)),
                        codec9.encode(getter9.apply(obj)),
                        codec10.encode(getter10.apply(obj)),
                        codec11.encode(getter11.apply(obj)),
                        codec12.encode(getter12.apply(obj)),
                        codec13.encode(getter13.apply(obj)),
                        codec14.encode(getter14.apply(obj)),
                        codec15.encode(getter15.apply(obj)),
                        codec16.encode(getter16.apply(obj)));
            }

            @Override
            public T decode(@NotNull Data data, int dataVersion) {
                var list = data.getList();
                return constructor.apply(
                        codec1.decode(list.get(0), dataVersion),
                        codec2.decode(list.get(1), dataVersion),
                        codec3.decode(list.get(2), dataVersion),
                        codec4.decode(list.get(3), dataVersion),
                        codec5.decode(list.get(4), dataVersion),
                        codec6.decode(list.get(5), dataVersion),
                        codec7.decode(list.get(6), dataVersion),
                        codec8.decode(list.get(7), dataVersion),
                        codec9.decode(list.get(8), dataVersion),
                        codec10.decode(list.get(9), dataVersion),
                        codec11.decode(list.get(10), dataVersion),
                        codec12.decode(list.get(11), dataVersion),
                        codec13.decode(list.get(12), dataVersion),
                        codec14.decode(list.get(13), dataVersion),
                        codec15.decode(list.get(14), dataVersion),
                        codec16.decode(list.get(15), dataVersion));
            }
        };
    }

    /**
     * Resolves the persist-side codec of a type: the {@link DataCodec} half of what
     * {@link com.gto.datasynclib.DataSyncCodec#get(Class)} holds, so a caller that only serializes to
     * disk works with the bare half the combined codec was built from instead of the wrapper.
     *
     * <p>The lookup rules are the combined registry's own: an exact class match, enums and object
     * arrays generated on demand, and a primitive resolved as its wrapper. A type without a codec —
     * including a type whose codec only exists on a {@code @Codec} annotation — resolves to
     * {@code null}.</p>
     *
     * @param type the class to resolve
     * @return the persist-side codec, or {@code null} if the type has none
     */
    @Nullable
    static <T> DataCodec<T> get(Class<T> type) {
        var codec = DataSyncCodec.get(type);
        return codec == null ? null : codec.toDataCodec();
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
    };
}
