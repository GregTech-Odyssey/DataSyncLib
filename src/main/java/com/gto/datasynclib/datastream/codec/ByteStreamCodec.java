package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.DataOps;
import com.mojang.datafixers.util.*;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Combined encoder/decoder interface for {@link FriendlyByteBuf}-based network transmission.
 *
 * <p>Extends both {@link ByteStreamDecoder} and {@link ByteStreamEncoder} for bidirectional
 * serialization. Contains built-in codec constants for all Java primitives, arrays, String,
 * UUID, and BigInteger — each auto-registering in the global {@link Codecs} registry.
 *
 * <p>Factory methods {@link #of} adapt from {@link DataCodec} or Mojang {@link com.mojang.serialization.Codec}.
 *
 * @param <T> the type this codec can encode and decode
 */
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

    static <K, V> ByteStreamCodec<V> map(ByteStreamCodec<K> codec, Function<? super V, ? extends K> encodeConverter, Function<? super K, ? extends V> decodeConverter) {
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
            buf.writeVarInt(obj);
        }

        @Override
        public Integer decode(FriendlyByteBuf buf) {
            return buf.readVarInt();
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
