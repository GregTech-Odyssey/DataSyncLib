package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.util.ByteBufCodecExtends;
import com.mojang.datafixers.util.*;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Combined encoder/decoder interface for buffer-based network transmission, with the buffer type as
 * an independent parameter — the shape of 1.21's {@code net.minecraft.network.codec.StreamCodec}.
 *
 * <p>{@code B} carries no bound and this interface never names a buffer: it only composes codecs
 * ({@link #of}, {@link #ofMember}, {@link #unit}, {@link #convert}, {@link #composite},
 * {@link #dispatch}, {@link #recursive}), re-types the buffer ({@link #mapStream}, {@link #cast}) and
 * runs the buffer-side {@link CodecOperation}s through {@link #apply}, so a codec written for one
 * buffer works wherever a broader one is expected — which is what {@link #composite}'s
 * {@code ? super B} component parameters are for. A {@code StreamCodec<ByteBuf, Integer>} is a
 * perfectly good component of a {@code StreamCodec<FriendlyByteBuf, MyType>}.</p>
 *
 * <p>Everything that <em>does</em> name a buffer lives in {@link ByteBufCodecs}: the built-in
 * constants (the numeric family is {@code StreamCodec<ByteBuf, …>}, the least a buffer can be while
 * still carrying a VarInt, while Minecraft-flavoured payloads need {@link FriendlyByteBuf}) and the
 * container builders, which write a length or a presence flag. The Minecraft value types live in
 * {@link ByteBufCodecExtends} — the Minecraft-type extension of
 * {@link ByteBufCodecs}, which therefore inherits every constant here. There is no per-interface
 * registry: runtime type lookup goes through
 * {@link com.gto.datasynclib.DataSyncCodec#get(Class)}, the single table for both
 * paths — {@link ByteBufCodecs#get(Class)} hands back this path's own half of the result.</p>
 *
 * <h3>Performance: the helper paths box</h3>
 * <p>{@link #convert} is generic over both its types, so a primitive travels as its wrapper; the same
 * is true of {@link ByteBufCodecs}'s container builders. For a type that is a few primitives and is
 * synchronized often, prefer a hand-written pair
 * ({@link ByteBufCodecExtends#VEC3I_CODEC} shows the shape) — primitive
 * <em>arrays</em> and the FastUtil primitive collections already have primitive-backed codecs
 * ({@link ByteBufCodecs#VAR_INT_ARRAY} and friends), and those helpers are for containers whose
 * elements are objects anyway.</p>
 *
 * <p>The persistence bridges ({@link DataSyncCodec#of(StreamCodec)}, {@link DataSyncCodec#of(ValueCodec)})
 * exist as static factories only, because they allocate a buffer of their own.</p>
 *
 * @param <B> the buffer this codec reads from and writes to — anything; the built-ins use
 *            {@link ByteBuf} or {@link FriendlyByteBuf}, depending on what their payload needs
 * @param <T> the type this codec can encode and decode
 */
public interface StreamCodec<B, T> extends StreamDecoder<B, T>, StreamEncoder<B, T> {

    /**
     * Pairs an independent encoder and decoder into a codec.
     */
    static <B, T> StreamCodec<B, T> of(StreamEncoder<B, ? super T> encoder, StreamDecoder<B, ? extends T> decoder) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, T obj) {
                encoder.encode(buf, obj);
            }

            @Override
            public T decode(B buf) {
                return decoder.decode(buf);
            }
        };
    }

    /**
     * {@link #of(StreamEncoder, StreamDecoder)} with the encoder in the <strong>value-first</strong>
     * form ({@link StreamMemberEncoder}), which lets a member method of the value itself stand in for
     * the write half:
     *
     * <pre>{@code
     * record Point(int x, int y) {
     *     void write(FriendlyByteBuf buf) { ... }
     *     static Point read(FriendlyByteBuf buf) { ... }
     * }
     *
     * StreamCodec<FriendlyByteBuf, Point> CODEC = StreamCodec.ofMember(Point::write, Point::read);
     * }</pre>
     *
     * <p>The decoder needs no such form: {@link StreamDecoder#decode} already takes only the buffer,
     * so a method reference binds to it directly. Both halves must name the same buffer type, since
     * the encoder is written against {@code B} rather than against {@link StreamEncoder}'s usual
     * {@code ? super B}.</p>
     */
    static <B, T> StreamCodec<B, T> ofMember(StreamMemberEncoder<B, ? super T> encoder, StreamDecoder<B, ? extends T> decoder) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, T obj) {
                encoder.encode(obj, buf);
            }

            @Override
            public T decode(B buf) {
                return decoder.decode(buf);
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
    static <B, K, V> StreamCodec<B, V> convert(StreamCodec<? super B, K> codec, Function<? super V, ? extends K> encodeConverter, Function<? super K, ? extends V> decodeConverter) {
        return new StreamCodec<>() {

            @Override
            public void encode(B buf, V obj) {
                codec.encode(buf, encodeConverter.apply(obj));
            }

            @Override
            public V decode(B buf) {
                return decodeConverter.apply(codec.decode(buf));
            }
        };
    }


    // ===== Optional adapters =====


    // ===== Instance mirror =====
    // Only the builder that touches no buffer has an instance form: every other builder would have to
    // fix B to the buffer it can write, which is what the parameter is there to keep open. A
    // CombinedCodec — whose stream half is pinned to FriendlyByteBuf — still offers the instance forms
    // that combine both paths into one codec.

    /**
     * Adapts this codec through a pair of converter functions — the instance form of
     * {@link #convert(StreamCodec, Function, Function)}, with this codec's type as the
     * transported type {@code K}.
     */
    default <V> StreamCodec<B, V> convert(Function<? super V, ? extends T> encodeConverter, Function<? super T, ? extends V> decodeConverter) {
        return convert(this, encodeConverter, decodeConverter);
    }

    /**
     * Runs a {@link CodecOperation} on this codec — the entry point to the 1.21 operations that
     * cannot be a plain builder of their own because they need the element codec <em>and</em> their
     * own argument:
     *
     * <pre>{@code
     * StreamCodec<FriendlyByteBuf, List<String>> LIST_CODEC =
     *         ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());
     * }</pre>
     *
     * @param operation the transformation to apply to this codec
     * @param <O>       the value type the produced codec handles
     */
    default <O> StreamCodec<B, O> apply(CodecOperation<B, T, O> operation) {
        return operation.apply(this);
    }

    /**
     * Re-types this codec onto a narrower buffer, by mapping the buffer that arrives into the one this
     * codec reads and writes — the shape of 1.21's {@code mapStream}.
     *
     * <p>It is for the places where the buffer type has to be named exactly (a field, a dispatch map,
     * a method that only accepts the narrow type) even though the codec itself never looks at more
     * than the buffer methods it uses: {@code CODEC.mapStream(b -> b)} states that a
     * {@code StreamCodec<ByteBuf, …>} is fine to use as a
     * {@code StreamCodec<FriendlyByteBuf, …>}.</p>
     *
     * @param function maps the incoming buffer to the one this codec reads and writes
     * @param <O>      the buffer type the produced codec accepts
     */
    default <O extends ByteBuf> StreamCodec<O, T> mapStream(Function<O, ? extends B> function) {
        var codec = this;
        return new StreamCodec<>() {

            @Override
            public void encode(O buf, T obj) {
                codec.encode(function.apply(buf), obj);
            }

            @Override
            public T decode(O buf) {
                return codec.decode(function.apply(buf));
            }
        };
    }

    /**
     * Re-types this codec's buffer to a subtype without touching the payload — 1.21's {@code cast}.
     *
     * <p>This is an unchecked cast, and it is safe exactly as long as the codec never needs the buffer
     * to be more than the {@code B} it was written against; a codec that really does depend on the
     * narrower type would only fail at the call site that matches it. Prefer
     * {@link #mapStream(Function)} when the buffer really does have to be converted.</p>
     *
     * @param <S> the buffer type the produced codec accepts
     */
    @SuppressWarnings("unchecked")
    default <S extends B> StreamCodec<S, T> cast() {
        return (StreamCodec<S, T>) this;
    }

    /**
     * Dispatches on this codec's own value: the discriminator is written with this codec, and
     * {@code codecGetter} picks the codec of the payload from it — the instance form of
     * {@link #dispatch(StreamCodec, Function, Function)} for the common 1.21 shape where the
     * receiver <em>is</em> the discriminator codec:
     *
     * <pre>{@code
     * StreamCodec<FriendlyByteBuf, Shape> SHAPE =
     *         ByteBufCodecs.VAR_INT.dispatch(Shape::kind, kind -> codecs.get(kind));
     * }</pre>
     *
     * <p>The payload codecs must accept this codec's buffer at least — the receiver's {@code B} is
     * what {@code ? super B} is measured against — so a discriminator codec written for the broad
     * {@link ByteBuf} needs {@link #cast()} (or {@link #mapStream}) before it can dispatch to codecs
     * pinned to {@code FriendlyByteBuf}.</p>
     *
     * @param type        extracts the discriminator from a value
     * @param codecGetter selects the payload codec for a discriminator value
     * @param <U>         the base type the produced codec handles
     */
    default <U> StreamCodec<B, U> dispatch(Function<? super U, ? extends T> type, Function<? super T, ? extends StreamCodec<? super B, ? extends U>> codecGetter) {
        return dispatch(this, type, codecGetter);
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
    static <B, T> StreamCodec<B, T> unit(T value) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
                // no-op: nothing to write
            }

            @Override
            public T decode(B buf) {
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
    static <B, T, F1> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            Function<? super F1, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
            }

            @Override
            public T decode(B buf) {
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
    static <B, T, F1, F2> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            BiFunction<? super F1, ? super F2, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
            }

            @Override
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            Function3<? super F1, ? super F2, ? super F3, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
            }

            @Override
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            Function4<? super F1, ? super F2, ? super F3, ? super F4, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
            }

            @Override
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            Function5<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
            }

            @Override
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            Function6<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
            }

            @Override
            public T decode(B buf) {
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
    static <B, T, D> StreamCodec<B, T> dispatch(
            StreamCodec<? super B, D> discriminatorCodec,
            Function<? super T, ? extends D> discriminator,
            Function<? super D, ? extends StreamCodec<? super B, ? extends T>> codecGetter) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
                D type = discriminator.apply(obj);
                discriminatorCodec.encode(buf, type);
                StreamCodec<? super B, ? extends T> codec = codecGetter.apply(type);
                ((StreamCodec<B, T>) codec).encode(buf, obj);
            }

            @Override
            public T decode(B buf) {
                D type = discriminatorCodec.decode(buf);
                StreamCodec<? super B, ? extends T> codec = codecGetter.apply(type);
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
     * StreamCodec<B, Node> NODE_CODEC = StreamCodec.recursive(self ->
     *     StreamCodec.composite(
     *         ByteBufCodecs.STRING_UTF8, Node::value,
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
    static <B, T> StreamCodec<B, T> recursive(Function<StreamCodec<B, T>, StreamCodec<B, T>> wrapped) {
        return new StreamCodec<>() {
            private StreamCodec<B, T> resolved;

            private StreamCodec<B, T> resolve() {
                if (resolved == null) resolved = wrapped.apply(this);
                return resolved;
            }

            @Override
            public void encode(B buf, T obj) {
                resolve().encode(buf, obj);
            }

            @Override
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6, F7> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            StreamCodec<? super B, F7> codec7, Function<? super T, ? extends F7> getter7,
            Function7<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
                codec1.encode(buf, getter1.apply(obj));
                codec2.encode(buf, getter2.apply(obj));
                codec3.encode(buf, getter3.apply(obj));
                codec4.encode(buf, getter4.apply(obj));
                codec5.encode(buf, getter5.apply(obj));
                codec6.encode(buf, getter6.apply(obj));
                codec7.encode(buf, getter7.apply(obj));
            }

            @Override
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6, F7, F8> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            StreamCodec<? super B, F7> codec7, Function<? super T, ? extends F7> getter7,
            StreamCodec<? super B, F8> codec8, Function<? super T, ? extends F8> getter8,
            Function8<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
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
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6, F7, F8, F9> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            StreamCodec<? super B, F7> codec7, Function<? super T, ? extends F7> getter7,
            StreamCodec<? super B, F8> codec8, Function<? super T, ? extends F8> getter8,
            StreamCodec<? super B, F9> codec9, Function<? super T, ? extends F9> getter9,
            Function9<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
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
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            StreamCodec<? super B, F7> codec7, Function<? super T, ? extends F7> getter7,
            StreamCodec<? super B, F8> codec8, Function<? super T, ? extends F8> getter8,
            StreamCodec<? super B, F9> codec9, Function<? super T, ? extends F9> getter9,
            StreamCodec<? super B, F10> codec10, Function<? super T, ? extends F10> getter10,
            Function10<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
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
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            StreamCodec<? super B, F7> codec7, Function<? super T, ? extends F7> getter7,
            StreamCodec<? super B, F8> codec8, Function<? super T, ? extends F8> getter8,
            StreamCodec<? super B, F9> codec9, Function<? super T, ? extends F9> getter9,
            StreamCodec<? super B, F10> codec10, Function<? super T, ? extends F10> getter10,
            StreamCodec<? super B, F11> codec11, Function<? super T, ? extends F11> getter11,
            Function11<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
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
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            StreamCodec<? super B, F7> codec7, Function<? super T, ? extends F7> getter7,
            StreamCodec<? super B, F8> codec8, Function<? super T, ? extends F8> getter8,
            StreamCodec<? super B, F9> codec9, Function<? super T, ? extends F9> getter9,
            StreamCodec<? super B, F10> codec10, Function<? super T, ? extends F10> getter10,
            StreamCodec<? super B, F11> codec11, Function<? super T, ? extends F11> getter11,
            StreamCodec<? super B, F12> codec12, Function<? super T, ? extends F12> getter12,
            Function12<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
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
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            StreamCodec<? super B, F7> codec7, Function<? super T, ? extends F7> getter7,
            StreamCodec<? super B, F8> codec8, Function<? super T, ? extends F8> getter8,
            StreamCodec<? super B, F9> codec9, Function<? super T, ? extends F9> getter9,
            StreamCodec<? super B, F10> codec10, Function<? super T, ? extends F10> getter10,
            StreamCodec<? super B, F11> codec11, Function<? super T, ? extends F11> getter11,
            StreamCodec<? super B, F12> codec12, Function<? super T, ? extends F12> getter12,
            StreamCodec<? super B, F13> codec13, Function<? super T, ? extends F13> getter13,
            Function13<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
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
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            StreamCodec<? super B, F7> codec7, Function<? super T, ? extends F7> getter7,
            StreamCodec<? super B, F8> codec8, Function<? super T, ? extends F8> getter8,
            StreamCodec<? super B, F9> codec9, Function<? super T, ? extends F9> getter9,
            StreamCodec<? super B, F10> codec10, Function<? super T, ? extends F10> getter10,
            StreamCodec<? super B, F11> codec11, Function<? super T, ? extends F11> getter11,
            StreamCodec<? super B, F12> codec12, Function<? super T, ? extends F12> getter12,
            StreamCodec<? super B, F13> codec13, Function<? super T, ? extends F13> getter13,
            StreamCodec<? super B, F14> codec14, Function<? super T, ? extends F14> getter14,
            Function14<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
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
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            StreamCodec<? super B, F7> codec7, Function<? super T, ? extends F7> getter7,
            StreamCodec<? super B, F8> codec8, Function<? super T, ? extends F8> getter8,
            StreamCodec<? super B, F9> codec9, Function<? super T, ? extends F9> getter9,
            StreamCodec<? super B, F10> codec10, Function<? super T, ? extends F10> getter10,
            StreamCodec<? super B, F11> codec11, Function<? super T, ? extends F11> getter11,
            StreamCodec<? super B, F12> codec12, Function<? super T, ? extends F12> getter12,
            StreamCodec<? super B, F13> codec13, Function<? super T, ? extends F13> getter13,
            StreamCodec<? super B, F14> codec14, Function<? super T, ? extends F14> getter14,
            StreamCodec<? super B, F15> codec15, Function<? super T, ? extends F15> getter15,
            Function15<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
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
            public T decode(B buf) {
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
    static <B, T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15, F16> StreamCodec<B, T> composite(
            StreamCodec<? super B, F1> codec1, Function<? super T, ? extends F1> getter1,
            StreamCodec<? super B, F2> codec2, Function<? super T, ? extends F2> getter2,
            StreamCodec<? super B, F3> codec3, Function<? super T, ? extends F3> getter3,
            StreamCodec<? super B, F4> codec4, Function<? super T, ? extends F4> getter4,
            StreamCodec<? super B, F5> codec5, Function<? super T, ? extends F5> getter5,
            StreamCodec<? super B, F6> codec6, Function<? super T, ? extends F6> getter6,
            StreamCodec<? super B, F7> codec7, Function<? super T, ? extends F7> getter7,
            StreamCodec<? super B, F8> codec8, Function<? super T, ? extends F8> getter8,
            StreamCodec<? super B, F9> codec9, Function<? super T, ? extends F9> getter9,
            StreamCodec<? super B, F10> codec10, Function<? super T, ? extends F10> getter10,
            StreamCodec<? super B, F11> codec11, Function<? super T, ? extends F11> getter11,
            StreamCodec<? super B, F12> codec12, Function<? super T, ? extends F12> getter12,
            StreamCodec<? super B, F13> codec13, Function<? super T, ? extends F13> getter13,
            StreamCodec<? super B, F14> codec14, Function<? super T, ? extends F14> getter14,
            StreamCodec<? super B, F15> codec15, Function<? super T, ? extends F15> getter15,
            StreamCodec<? super B, F16> codec16, Function<? super T, ? extends F16> getter16,
            Function16<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? super F16, ? extends T> constructor) {
        return new StreamCodec<>() {
            @Override
            public void encode(B buf, T obj) {
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
            public T decode(B buf) {
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
     * A deferred codec transformation: given the codec of an "inner" value, produce the codec of the
     * container around it — the shape of 1.21's {@code StreamCodec.CodecOperation}.
     *
     * <p>It exists so a builder that needs the element codec <em>as well as</em> its own argument can
     * still be read fluently on the codec it wraps, without the library declaring an instance method
     * per container type: {@code STRING_UTF8.apply(ByteBufCodecs.list())} is the
     * {@code List<String>} codec, {@code STRING_UTF8.apply(ByteBufCodecs.list(8))} is the bounded one,
     * and {@code STRING_UTF8.apply(ByteBufCodecs.array(String[]::new))} is the {@code String[]} one.
     * Reach it through {@link StreamCodec#apply(CodecOperation)}; the operations themselves live in
     * {@link ByteBufCodecs}, next to the codecs they wrap.</p>
     *
     * @param <B> the buffer both the incoming and the produced codec read and write
     * @param <S> the value type the incoming codec handles — the element, in every operation here
     * @param <T> the value type the produced codec handles — the container
     */
    @FunctionalInterface
    interface CodecOperation<B, S, T> {

        /**
         * Wraps {@code codec} into the codec for the surrounding type.
         */
        StreamCodec<B, T> apply(StreamCodec<B, S> codec);
    }

}
