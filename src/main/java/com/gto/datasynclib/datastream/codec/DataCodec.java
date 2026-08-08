package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.datastream.data.*;
import com.mojang.datafixers.util.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;

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

                }
                return (T[]) Array.newInstance(type, 0);
            }
        };
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

        private static final Reference2ReferenceOpenHashMap<Class<?>, DataCodec<?>> CODECS = new Reference2ReferenceOpenHashMap<>();
    }
}
