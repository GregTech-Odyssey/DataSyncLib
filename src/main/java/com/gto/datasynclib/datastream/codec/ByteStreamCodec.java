package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.DataOps;
import com.mojang.datafixers.util.*;
import com.mojang.serialization.Codec;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Combined encoder/decoder interface for {@link FriendlyByteBuf}-based network transmission.
 *
 * <p>Extends both {@link ByteStreamDecoder} and {@link ByteStreamEncoder} for bidirectional
 * serialization. Contains built-in codec constants for all Java primitives, arrays, String,
 * UUID, and BigInteger. There is no per-interface registry: runtime type lookup goes through
 * {@link com.gto.datasynclib.DataSyncCodec#get(Class)}, the single table for both paths, and
 * {@link #get(Class)} hands back this path's own half of the result.</p>
 *
 * <p>Factory methods {@link #of} adapt from {@link DataCodec} or Mojang {@link com.mojang.serialization.Codec}.
 * {@link #convert} adapts an existing codec with converter functions, while
 * {@link #map} / {@link #collection} / {@link #array} build container codecs.</p>
 *
 * <p>Every builder that takes a codec also has an instance form on the codec itself, with the
 * receiver replacing that codec argument and the remaining parameters in the static order —
 * {@code STRING_CODEC.optional()}, {@code STRING_CODEC.collection(ArrayList::new)},
 * {@code STRING_CODEC.asKey(HashMap::new, INT_CODEC)}, {@code STRING_CODEC.toDataCodec()}, … .
 * {@link #optional} additionally treats a value equal to a default as absent: it writes the same
 * {@code false} marker as {@code null} and decodes back to the default; the {@link Supplier}
 * overload and its lambda caveat are documented on the method.</p>
 *
 * <h3>Performance: the helper paths box</h3>
 * <p>{@link #convert}, {@link #map}, {@link #collection} and {@link #array} are generic over the
 * element type, so primitive elements are boxed on the way in and out. Use them for object
 * payloads; for a type that is a few primitives and is synchronized often, prefer a hand-written
 * pair ({@link com.gto.datasynclib.util.StreamCodecs#VEC3I_CODEC} shows the shape) — primitive
 * <em>arrays</em> already have primitive-backed codecs, so {@link #array} is only needed for
 * object arrays.</p>
 *
 * @param <T> the type this codec can encode and decode
 */
public interface ByteStreamCodec<T> extends ByteStreamDecoder<T>, ByteStreamEncoder<T> {

    /**
     * Pairs an independent encoder and decoder into a codec.
     */
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

    /**
     * Adapts a disk codec by writing its {@link Data} payload inline
     * ({@link Data#writeData(FriendlyByteBuf, Data)}) — same layout as the persistence form,
     * without the extra length-prefixed byte array used by
     * {@link com.gto.datasynclib.DataSyncCodec#of(DataCodec)}.
     */
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

    /**
     * Adapts a Mojang DFU {@link Codec} through {@link DataOps#INSTANCE}.
     *
     * <p>Failures surface as thrown exceptions from the {@code orElseThrow()} calls rather than
     * as a {@code DataResult} error, so this adapter is only safe for trusted data.</p>
     */
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

    /**
     * Adapts a codec of another type through a pair of converter functions.
     *
     * <p>Both directions pass through the generic value, so a primitive-based {@code V}/{@code K}
     * is boxed on every encode and decode; see the class documentation for when to hand-write.</p>
     *
     * @param codec           codec of the transported type {@code K}
     * @param encodeConverter {@code V -> K}, applied before encoding
     * @param decodeConverter {@code K -> V}, applied after decoding
     */
    static <K, V> ByteStreamCodec<V> convert(ByteStreamCodec<K> codec, Function<? super V, ? extends K> encodeConverter, Function<? super K, ? extends V> decodeConverter) {
        return new ByteStreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, V obj) {
                codec.encode(buf, encodeConverter.apply(obj));
            }

            @Override
            public V decode(FriendlyByteBuf buf) {
                return decodeConverter.apply(codec.decode(buf));
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
    static <K, V, M extends Map<K, V>> ByteStreamCodec<M> map(IntFunction<M> function, ByteStreamCodec<K> keyCodec, ByteStreamCodec<V> valueCodec) {
        return new ByteStreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, M obj) {
                buf.writeVarInt(obj.size());
                obj.forEach((k, v) -> {
                    keyCodec.encode(buf, k);
                    valueCodec.encode(buf, v);
                });
            }

            @Override
            public M decode(FriendlyByteBuf buf) {
                int size = buf.readVarInt();
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
    static <T, C extends Collection<T>> ByteStreamCodec<C> collection(IntFunction<C> function, ByteStreamCodec<T> codec) {
        return new ByteStreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, C obj) {
                buf.writeVarInt(obj.size());
                obj.forEach(o -> codec.encode(buf, o));
            }

            @Override
            public C decode(FriendlyByteBuf buf) {
                int size = buf.readVarInt();
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
    static <T> ByteStreamCodec<T[]> array(Class<T> type, ByteStreamCodec<T> codec) {
        return new ByteStreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, T[] obj) {
                buf.writeVarInt(obj.length);
                for (T o : obj) {
                    codec.encode(buf, o);
                }
            }

            @Override
            public T[] decode(FriendlyByteBuf buf) {
                int size = buf.readVarInt();
                var array = (T[]) Array.newInstance(type, size);
                for (int i = 0; i < size; i++) array[i] = codec.decode(buf);
                return array;
            }
        };
    }


    // ===== Optional adapters =====

    /**
     * This codec made nullable: {@code null} is written as a {@code false} boolean and decodes back
     * to {@code null}, while any other value is preceded by a {@code true} boolean and then written
     * by this codec.
     *
     * <p>The boolean is the stream's substitute for the type tag the data path gets for free, so an
     * absent value costs one byte and a present one costs one extra byte; a {@link CombinedCodec}
     * overrides this method to wrap both paths at once.</p>
     *
     * <p>Equivalent to {@code ByteStreamCodec.optional(this)}.</p>
     */
    default ByteStreamCodec<T> optional() {
        return ByteStreamCodec.optional(this);
    }

    /**
     * This codec with a default: a {@code null} value, or one that
     * {@link Objects#equals(Object, Object) equals} {@code defaultValue}, writes the same
     * {@code false} marker as the absent form of {@link #optional()} and decodes back to the
     * default instead of {@code null}. Any other value writes {@code true} followed by this codec's
     * payload.
     *
     * <p>Equivalent to {@code ByteStreamCodec.optional(this, defaultValue)}.</p>
     *
     * @param defaultValue the value that is written as absent; a {@code null} default degrades to
     *                     plain {@link #optional()}
     */
    default ByteStreamCodec<T> optional(T defaultValue) {
        return ByteStreamCodec.optional(this, defaultValue);
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
     * an absent value, so it should be side-effect free and cheap; a fresh mutable default is
     * re-created on every decode, which is what you want for collections.</p>
     *
     * <p>Equivalent to {@code ByteStreamCodec.optional(this, defaultSupplier)}.</p>
     *
     * @param defaultSupplier supplies the value that is written as absent
     */
    default ByteStreamCodec<T> optional(Supplier<? extends T> defaultSupplier) {
        return ByteStreamCodec.optional(this, defaultSupplier);
    }

    /**
     * {@link #optional()}, as a builder over an arbitrary codec.
     *
     * @param codec codec for the present (non-{@code null}) value
     * @param <T>   the value type
     */
    static <T> ByteStreamCodec<T> optional(ByteStreamCodec<T> codec) {
        return new ByteStreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                if (obj == null) {
                    buf.writeBoolean(false);
                } else {
                    buf.writeBoolean(true);
                    codec.encode(buf, obj);
                }
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return buf.readBoolean() ? codec.decode(buf) : null;
            }
        };
    }

    /**
     * {@link #optional(Object)}, as a builder over an arbitrary codec.
     *
     * @param codec        codec for the present value
     * @param defaultValue the value that is written as absent; a {@code null} default degrades to
     *                     plain {@link #optional(ByteStreamCodec)}
     * @param <T>          the value type
     */
    static <T> ByteStreamCodec<T> optional(ByteStreamCodec<T> codec, T defaultValue) {
        return new ByteStreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                if (obj == null || Objects.equals(obj, defaultValue)) {
                    buf.writeBoolean(false);
                } else {
                    buf.writeBoolean(true);
                    codec.encode(buf, obj);
                }
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return buf.readBoolean() ? codec.decode(buf) : defaultValue;
            }
        };
    }

    /**
     * {@link #optional(Supplier)}, as a builder over an arbitrary codec.
     *
     * <p>As on the instance form, a bare lambda or method reference is ambiguous between this
     * overload and {@link #optional(ByteStreamCodec, Object)} — {@code optional(codec, ArrayList::new)}
     * does not compile — so pass a typed {@code Supplier<T>} variable or cast the lambda.</p>
     *
     * @param codec           codec for the present value
     * @param defaultSupplier supplies the value that is written as absent
     * @param <T>             the value type
     */
    static <T> ByteStreamCodec<T> optional(ByteStreamCodec<T> codec, Supplier<? extends T> defaultSupplier) {
        return new ByteStreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                if (obj == null || Objects.equals(obj, defaultSupplier.get())) {
                    buf.writeBoolean(false);
                } else {
                    buf.writeBoolean(true);
                    codec.encode(buf, obj);
                }
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return buf.readBoolean() ? codec.decode(buf) : defaultSupplier.get();
            }
        };
    }

    // ===== Instance builder mirrors =====
    // Every static builder that takes a codec also exists as an instance method on the codec
    // itself: the receiver replaces that codec argument and the remaining parameters keep the
    // static order and meaning. A CombinedCodec overrides the ones both halves declare, so the
    // same call works — with a DataSyncCodec result — on a codec that covers both paths.

    /**
     * Adapts this stream codec to the persistence path — the instance form of
     * {@link DataCodec#of(ByteStreamCodec)}: the stream bytes are carried inside a
     * {@link com.gto.datasynclib.datastream.data.ByteArrayData}, so the disk form mirrors the wire
     * form at the cost of an extra buffer round-trip.
     */
    default DataCodec<T> toDataCodec() {
        return DataCodec.of(this);
    }

    /**
     * Pairs this stream codec with the data codec derived from it ({@link #toDataCodec()}) into one
     * combined codec — the instance form of {@link CombinedCodec#of(ByteStreamCodec)}.
     */
    default DataSyncCodec<T> toDataSyncCodec() {
        return DataSyncCodec.of(this);
    }

    /**
     * Adapts this codec through a pair of converter functions — the instance form of
     * {@link #convert(ByteStreamCodec, Function, Function)}, with this codec's type as the
     * transported type {@code K}.
     */
    default <V> ByteStreamCodec<V> convert(Function<? super V, ? extends T> encodeConverter, Function<? super T, ? extends V> decodeConverter) {
        return convert(this, encodeConverter, decodeConverter);
    }

    /**
     * Map codec keyed by this codec, with {@code valueCodec} for the values — the instance form of
     * {@link #map(IntFunction, ByteStreamCodec, ByteStreamCodec)}: a VarInt size followed by the
     * key/value pairs.
     */
    default <V, M extends Map<T, V>> ByteStreamCodec<M> asKey(IntFunction<M> function, ByteStreamCodec<V> valueCodec) {
        return map(function, this, valueCodec);
    }

    /**
     * Map codec valued by this codec, with {@code keyCodec} for the keys — the instance form of
     * {@link #map(IntFunction, ByteStreamCodec, ByteStreamCodec)}, with this codec on the value
     * side.
     */
    default <K, M extends Map<K, T>> ByteStreamCodec<M> asValue(IntFunction<M> function, ByteStreamCodec<K> keyCodec) {
        return map(function, keyCodec, this);
    }

    /**
     * Collection codec over this codec's elements — the instance form of
     * {@link #collection(IntFunction, ByteStreamCodec)}.
     */
    default <C extends Collection<T>> ByteStreamCodec<C> collection(IntFunction<C> function) {
        return collection(function, this);
    }

    /**
     * Object-array codec over this codec's elements — the instance form of
     * {@link #array(Class, ByteStreamCodec)}. A primitive array has its own codec and does not need
     * this.
     */
    default ByteStreamCodec<T[]> array(Class<T> type) {
        return array(type, this);
    }

    /**
     * Creates a codec that writes nothing on encode (no-op) and always returns
     * the given constant on decode.
     *
     * <p>Useful for sentinel values, version markers, or fields where the value
     * is always the same regardless of what passes through the stream.
     *
     * @param <T>   the value type
     * @param value the constant value to return on every decode
     * @return a codec that ignores all input and returns {@code value}
     */
    static <T> ByteStreamCodec<T> unit(T value) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                // no-op: nothing to write
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return value;
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from a single field.
     *
     * <p>Encoding extracts the field via {@code getter1} and writes it with {@code codec1}.
     * Decoding reads with {@code codec1} and constructs {@code T} via {@code constructor}.
     *
     * @param <T>         the target type
     * @param <F1>        the field type
     * @param codec1      codec for the field
     * @param getter1     extracts the field from {@code T}
     * @param constructor creates {@code T} from the decoded field
     * @return a composite codec for {@code T}
     */
    static <T, F1> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            Function<? super F1, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(codec1.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from two fields.
     *
     * @param <T>         the target type
     * @param <F1>        the first field type
     * @param <F2>        the second field type
     * @param codec1      codec for the first field
     * @param getter1     extracts the first field from {@code T}
     * @param codec2      codec for the second field
     * @param getter2     extracts the second field from {@code T}
     * @param constructor creates {@code T} from the two decoded fields
     * @return a composite codec for {@code T}
     */
    static <T, F1, F2> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            BiFunction<? super F1, ? super F2, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from three fields.
     *
     * @param <T>         the target type
     * @param <F1>        the first field type
     * @param <F2>        the second field type
     * @param <F3>        the third field type
     * @param codec1      codec for the first field
     * @param getter1     extracts the first field from {@code T}
     * @param codec2      codec for the second field
     * @param getter2     extracts the second field from {@code T}
     * @param codec3      codec for the third field
     * @param getter3     extracts the third field from {@code T}
     * @param constructor creates {@code T} from the three decoded fields
     * @return a composite codec for {@code T}
     */
    static <T, F1, F2, F3> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            Function3<? super F1, ? super F2, ? super F3, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from four fields.
     *
     * @param <T>         the target type
     * @param <F1>        the first field type
     * @param <F2>        the second field type
     * @param <F3>        the third field type
     * @param <F4>        the fourth field type
     * @param codec1      codec for the first field
     * @param getter1     extracts the first field from {@code T}
     * @param codec2      codec for the second field
     * @param getter2     extracts the second field from {@code T}
     * @param codec3      codec for the third field
     * @param getter3     extracts the third field from {@code T}
     * @param codec4      codec for the fourth field
     * @param getter4     extracts the fourth field from {@code T}
     * @param constructor creates {@code T} from the four decoded fields
     * @return a composite codec for {@code T}
     */
    static <T, F1, F2, F3, F4> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            Function4<? super F1, ? super F2, ? super F3, ? super F4, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from five fields.
     *
     * @param <T>         the target type
     * @param <F1>        the first field type
     * @param <F2>        the second field type
     * @param <F3>        the third field type
     * @param <F4>        the fourth field type
     * @param <F5>        the fifth field type
     * @param codec1      codec for the first field
     * @param getter1     extracts the first field from {@code T}
     * @param codec2      codec for the second field
     * @param getter2     extracts the second field from {@code T}
     * @param codec3      codec for the third field
     * @param getter3     extracts the third field from {@code T}
     * @param codec4      codec for the fourth field
     * @param getter4     extracts the fourth field from {@code T}
     * @param codec5      codec for the fifth field
     * @param getter5     extracts the fifth field from {@code T}
     * @param constructor creates {@code T} from the five decoded fields
     * @return a composite codec for {@code T}
     */
    static <T, F1, F2, F3, F4, F5> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            Function5<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from six fields.
     *
     * @param <T>         the target type
     * @param <F1>        the first field type
     * @param <F2>        the second field type
     * @param <F3>        the third field type
     * @param <F4>        the fourth field type
     * @param <F5>        the fifth field type
     * @param <F6>        the sixth field type
     * @param codec1      codec for the first field
     * @param getter1     extracts the first field from {@code T}
     * @param codec2      codec for the second field
     * @param getter2     extracts the second field from {@code T}
     * @param codec3      codec for the third field
     * @param getter3     extracts the third field from {@code T}
     * @param codec4      codec for the fourth field
     * @param getter4     extracts the fourth field from {@code T}
     * @param codec5      codec for the fifth field
     * @param getter5     extracts the fifth field from {@code T}
     * @param codec6      codec for the sixth field
     * @param getter6     extracts the sixth field from {@code T}
     * @param constructor creates {@code T} from the six decoded fields
     * @return a composite codec for {@code T}
     */
    static <T, F1, F2, F3, F4, F5, F6> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            Function6<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf));
            }
        };
    }

    /**
     * Creates a polymorphic codec that dispatches based on a discriminator value.
     *
     * <p>On encode, extracts the discriminator from the object via {@code discriminator},
     * writes it with {@code discriminatorCodec}, then delegates to the codec selected by
     * {@code codecGetter}. On decode, reads the discriminator first, selects the codec,
     * then reads the value.
     *
     * @param <T>                the base type
     * @param <D>                the discriminator type
     * @param discriminatorCodec codec for the discriminator value
     * @param discriminator      extracts the discriminator from an object
     * @param codecGetter        selects the codec for a given discriminator value
     * @return a dispatched codec for {@code T}
     */
    static <T, D> ByteStreamCodec<T> dispatch(
            ByteStreamCodec<D> discriminatorCodec,
            Function<? super T, ? extends D> discriminator,
            Function<? super D, ? extends ByteStreamCodec<? extends T>> codecGetter) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                D type = discriminator.apply(obj);
                discriminatorCodec.encode(buf, type);
                ByteStreamCodec<? extends T> codec = codecGetter.apply(type);
                ((ByteStreamCodec<T>) codec).encode(buf, obj);
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                D type = discriminatorCodec.decode(buf);
                ByteStreamCodec<? extends T> codec = codecGetter.apply(type);
                return codec.decode(buf);
            }
        };
    }

    /**
     * Creates a codec that can reference itself, enabling serialization of recursive
     * data structures such as trees and linked lists.
     *
     * <p>The {@code wrapped} function receives a placeholder codec (which delegates to
     * the final resolved codec once initialized) as its sole argument. Use this
     * placeholder inside the returned codec wherever a self-reference is needed.
     *
     * <p>Example — a binary tree node:
     * <pre>{@code
     * record Node(String value, Node left, Node right) {}
     *
     * ByteStreamCodec<Node> NODE_CODEC = ByteStreamCodec.recursive(self ->
     *     ByteStreamCodec.composite(
     *         ByteStreamCodec.STRING_CODEC, Node::value,
     *         self, Node::left,
     *         self, Node::right,
     *         Node::new
     *     )
     * );
     * }</pre>
     *
     * @param <T>     the target type
     * @param wrapped a function that receives a self-referencing codec and returns
     *                the actual codec (which may reference the argument recursively)
     * @return a codec for {@code T} that supports self-referential structures
     */
    static <T> ByteStreamCodec<T> recursive(Function<ByteStreamCodec<T>, ByteStreamCodec<T>> wrapped) {
        return new ByteStreamCodec<>() {
            private ByteStreamCodec<T> resolved;

            private ByteStreamCodec<T> resolve() {
                if (resolved == null) resolved = wrapped.apply(this);
                return resolved;
            }

            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                resolve().encode(buf, obj);
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return resolve().decode(buf);
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from seven fields.
     *
     * @param <T>         the target type
     * @param <F1>        the first field type
     * @param <F2>        the second field type
     * @param <F3>        the third field type
     * @param <F4>        the fourth field type
     * @param <F5>        the fifth field type
     * @param <F6>        the sixth field type
     * @param <F7>        the seventh field type
     * @param codec1      codec for the first field
     * @param getter1     extracts the first field from {@code T}
     * @param codec2      codec for the second field
     * @param getter2     extracts the second field from {@code T}
     * @param codec3      codec for the third field
     * @param getter3     extracts the third field from {@code T}
     * @param codec4      codec for the fourth field
     * @param getter4     extracts the fourth field from {@code T}
     * @param codec5      codec for the fifth field
     * @param getter5     extracts the fifth field from {@code T}
     * @param codec6      codec for the sixth field
     * @param getter6     extracts the sixth field from {@code T}
     * @param codec7      codec for the seventh field
     * @param getter7     extracts the seventh field from {@code T}
     * @param constructor creates {@code T} from the seven decoded fields
     * @return a composite codec for {@code T}
     */
    static <T, F1, F2, F3, F4, F5, F6, F7> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ByteStreamCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            Function7<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf),
                        codec7.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from eight fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ByteStreamCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ByteStreamCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            Function8<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
                codec8.encode(buf, getter8.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf),
                        codec7.decode(buf),
                        codec8.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from nine fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ByteStreamCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ByteStreamCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ByteStreamCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            Function9<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
                codec8.encode(buf, getter8.apply(obj));
                codec9.encode(buf, getter9.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf),
                        codec7.decode(buf),
                        codec8.decode(buf),
                        codec9.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from ten fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ByteStreamCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ByteStreamCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ByteStreamCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ByteStreamCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            Function10<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
                codec8.encode(buf, getter8.apply(obj));
                codec9.encode(buf, getter9.apply(obj));
                codec10.encode(buf, getter10.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf),
                        codec7.decode(buf),
                        codec8.decode(buf),
                        codec9.decode(buf),
                        codec10.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from eleven fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ByteStreamCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ByteStreamCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ByteStreamCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ByteStreamCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ByteStreamCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            Function11<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
                codec8.encode(buf, getter8.apply(obj));
                codec9.encode(buf, getter9.apply(obj));
                codec10.encode(buf, getter10.apply(obj));
                codec11.encode(buf, getter11.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf),
                        codec7.decode(buf),
                        codec8.decode(buf),
                        codec9.decode(buf),
                        codec10.decode(buf),
                        codec11.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from twelve fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ByteStreamCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ByteStreamCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ByteStreamCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ByteStreamCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ByteStreamCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            ByteStreamCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            Function12<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
                codec8.encode(buf, getter8.apply(obj));
                codec9.encode(buf, getter9.apply(obj));
                codec10.encode(buf, getter10.apply(obj));
                codec11.encode(buf, getter11.apply(obj));
                codec12.encode(buf, getter12.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf),
                        codec7.decode(buf),
                        codec8.decode(buf),
                        codec9.decode(buf),
                        codec10.decode(buf),
                        codec11.decode(buf),
                        codec12.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from thirteen fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ByteStreamCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ByteStreamCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ByteStreamCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ByteStreamCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ByteStreamCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            ByteStreamCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            ByteStreamCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            Function13<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
                codec8.encode(buf, getter8.apply(obj));
                codec9.encode(buf, getter9.apply(obj));
                codec10.encode(buf, getter10.apply(obj));
                codec11.encode(buf, getter11.apply(obj));
                codec12.encode(buf, getter12.apply(obj));
                codec13.encode(buf, getter13.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf),
                        codec7.decode(buf),
                        codec8.decode(buf),
                        codec9.decode(buf),
                        codec10.decode(buf),
                        codec11.decode(buf),
                        codec12.decode(buf),
                        codec13.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from fourteen fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ByteStreamCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ByteStreamCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ByteStreamCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ByteStreamCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ByteStreamCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            ByteStreamCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            ByteStreamCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            ByteStreamCodec<F14> codec14, Function<? super T, ? extends F14> getter14,
            Function14<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
                codec8.encode(buf, getter8.apply(obj));
                codec9.encode(buf, getter9.apply(obj));
                codec10.encode(buf, getter10.apply(obj));
                codec11.encode(buf, getter11.apply(obj));
                codec12.encode(buf, getter12.apply(obj));
                codec13.encode(buf, getter13.apply(obj));
                codec14.encode(buf, getter14.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf),
                        codec7.decode(buf),
                        codec8.decode(buf),
                        codec9.decode(buf),
                        codec10.decode(buf),
                        codec11.decode(buf),
                        codec12.decode(buf),
                        codec13.decode(buf),
                        codec14.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from fifteen fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ByteStreamCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ByteStreamCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ByteStreamCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ByteStreamCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ByteStreamCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            ByteStreamCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            ByteStreamCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            ByteStreamCodec<F14> codec14, Function<? super T, ? extends F14> getter14,
            ByteStreamCodec<F15> codec15, Function<? super T, ? extends F15> getter15,
            Function15<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
                codec8.encode(buf, getter8.apply(obj));
                codec9.encode(buf, getter9.apply(obj));
                codec10.encode(buf, getter10.apply(obj));
                codec11.encode(buf, getter11.apply(obj));
                codec12.encode(buf, getter12.apply(obj));
                codec13.encode(buf, getter13.apply(obj));
                codec14.encode(buf, getter14.apply(obj));
                codec15.encode(buf, getter15.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf),
                        codec7.decode(buf),
                        codec8.decode(buf),
                        codec9.decode(buf),
                        codec10.decode(buf),
                        codec11.decode(buf),
                        codec12.decode(buf),
                        codec13.decode(buf),
                        codec14.decode(buf),
                        codec15.decode(buf));
            }
        };
    }

    /**
     * Composes a codec for type {@code T} from sixteen fields.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15, F16> ByteStreamCodec<T> composite(
            ByteStreamCodec<F1> codec1, Function<? super T, ? extends F1> getter1,
            ByteStreamCodec<F2> codec2, Function<? super T, ? extends F2> getter2,
            ByteStreamCodec<F3> codec3, Function<? super T, ? extends F3> getter3,
            ByteStreamCodec<F4> codec4, Function<? super T, ? extends F4> getter4,
            ByteStreamCodec<F5> codec5, Function<? super T, ? extends F5> getter5,
            ByteStreamCodec<F6> codec6, Function<? super T, ? extends F6> getter6,
            ByteStreamCodec<F7> codec7, Function<? super T, ? extends F7> getter7,
            ByteStreamCodec<F8> codec8, Function<? super T, ? extends F8> getter8,
            ByteStreamCodec<F9> codec9, Function<? super T, ? extends F9> getter9,
            ByteStreamCodec<F10> codec10, Function<? super T, ? extends F10> getter10,
            ByteStreamCodec<F11> codec11, Function<? super T, ? extends F11> getter11,
            ByteStreamCodec<F12> codec12, Function<? super T, ? extends F12> getter12,
            ByteStreamCodec<F13> codec13, Function<? super T, ? extends F13> getter13,
            ByteStreamCodec<F14> codec14, Function<? super T, ? extends F14> getter14,
            ByteStreamCodec<F15> codec15, Function<? super T, ? extends F15> getter15,
            ByteStreamCodec<F16> codec16, Function<? super T, ? extends F16> getter16,
            Function16<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? super F16, ? extends T> constructor) {
        return new ByteStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
                codec8.encode(buf, getter8.apply(obj));
                codec9.encode(buf, getter9.apply(obj));
                codec10.encode(buf, getter10.apply(obj));
                codec11.encode(buf, getter11.apply(obj));
                codec12.encode(buf, getter12.apply(obj));
                codec13.encode(buf, getter13.apply(obj));
                codec14.encode(buf, getter14.apply(obj));
                codec15.encode(buf, getter15.apply(obj));
                codec16.encode(buf, getter16.apply(obj));
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return constructor.apply(
                        codec1.decode(buf),
                        codec2.decode(buf),
                        codec3.decode(buf),
                        codec4.decode(buf),
                        codec5.decode(buf),
                        codec6.decode(buf),
                        codec7.decode(buf),
                        codec8.decode(buf),
                        codec9.decode(buf),
                        codec10.decode(buf),
                        codec11.decode(buf),
                        codec12.decode(buf),
                        codec13.decode(buf),
                        codec14.decode(buf),
                        codec15.decode(buf),
                        codec16.decode(buf));
            }
        };
    }

    /**
     * Resolves the network-side codec of a type: the {@link ByteStreamCodec} half of what
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
    static <T> ByteStreamCodec<T> get(Class<T> type) {
        var codec = DataSyncCodec.get(type);
        return codec == null ? null : codec.toStreamCodec();
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
    };

    ByteStreamCodec<Integer> INT_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Integer obj) {
            buf.writeVarInt(obj);
        }

        @Override
        public Integer decode(FriendlyByteBuf buf) {
            return buf.readVarInt();
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
    };

    ByteStreamCodec<int[]> INTS_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, int[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeVarInt(i);
            }
        }

        @Override
        public int[] decode(FriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var ints = new int[length];
            for (int i = 0; i < length; i++) {
                ints[i] = buf.readVarInt();
            }
            return ints;
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
    };

    ByteStreamCodec<short[]> SHORTS_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, short[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeShort(i);
            }
        }

        @Override
        public short[] decode(FriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var shorts = new short[length];
            for (int i = 0; i < length; i++) {
                shorts[i] = buf.readShort();
            }
            return shorts;
        }
    };

    ByteStreamCodec<char[]> CHARS_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, char[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeChar(i);
            }
        }

        @Override
        public char[] decode(FriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var chars = new char[length];
            for (int i = 0; i < length; i++) {
                chars[i] = buf.readChar();
            }
            return chars;
        }
    };

    ByteStreamCodec<float[]> FLOATS_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, float[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeFloat(i);
            }
        }

        @Override
        public float[] decode(FriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var floats = new float[length];
            for (int i = 0; i < length; i++) {
                floats[i] = buf.readFloat();
            }
            return floats;
        }
    };

    ByteStreamCodec<double[]> DOUBLES_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, double[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeDouble(i);
            }
        }

        @Override
        public double[] decode(FriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var doubles = new double[length];
            for (int i = 0; i < length; i++) {
                doubles[i] = buf.readDouble();
            }
            return doubles;
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
    };
}
