package com.gto.datasynclib.datastream.codec;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * A {@link StreamDecoder} for a real buffer: the same read half, plus the helpers that have to read
 * something of their own — a {@code VarInt} size — and therefore need at least a {@link ByteBuf}
 * ({@link VarInts}).
 *
 * <p>{@link StreamDecoder} stays buffer-agnostic; this interface is the concrete-buffer flavour the
 * library itself uses, and a {@link ByteStreamCodec} is one of these as well as an encoder.</p>
 *
 * <p><strong>Boxing:</strong> the container helpers are generic over the element type, so primitives
 * are boxed; see {@link StreamCodec} for the full trade-off and when to hand-write instead.</p>
 *
 */
public interface ByteStreamDecoder {

    /**
     * Adapts a decoder of another type through a converter, as a {@code ByteStreamDecoder} — the
     * concrete-buffer counterpart of {@link StreamDecoder#convert}.
     */
    static <B extends ByteBuf, K, V> StreamDecoder<B, V> convert(StreamDecoder<? super B, ? extends K> decoder, Function<? super K, ? extends V> converter) {
        return buf -> converter.apply(decoder.decode(buf));
    }

    /**
     * Map decoder reading a {@code VarInt} size and that many key/value pairs, into the FastUtil map
     * the {@link ByteStreamEncoder#map} counterpart writes from.
     */
    static <B extends ByteBuf, K, V> StreamDecoder<B, Reference2ReferenceOpenHashMap<K, V>> map(StreamDecoder<? super B, ? extends K> keyDecoder, StreamDecoder<? super B, ? extends V> valueDecoder) {
        return buf -> {
            var size = VarInts.read(buf);
            var map = new Reference2ReferenceOpenHashMap<K, V>(size);
            for (int i = 0; i < size; i++) {
                map.put(keyDecoder.decode(buf), valueDecoder.decode(buf));
            }
            return map;
        };
    }

    /**
     * Map decoder into a map of the caller's choosing ({@code HashMap::new}, …), by expected size.
     */
    static <B extends ByteBuf, K, V, M extends Map<K, V>> StreamDecoder<B, M> map(IntFunction<M> function, StreamDecoder<? super B, ? extends K> keyDecoder, StreamDecoder<? super B, ? extends V> valueDecoder) {
        return buf -> {
            var size = VarInts.read(buf);
            var map = function.apply(size);
            for (int i = 0; i < size; i++) {
                map.put(keyDecoder.decode(buf), valueDecoder.decode(buf));
            }
            return map;
        };
    }

    /**
     * Collection decoder into a collection of the caller's choosing ({@code ArrayList::new}, …), by
     * expected size.
     */
    static <B extends ByteBuf, E, C extends Collection<E>> StreamDecoder<B, C> collection(IntFunction<C> function, StreamDecoder<? super B, ? extends E> decoder) {
        return buf -> {
            var size = VarInts.read(buf);
            var collection = function.apply(size);
            for (int i = 0; i < size; i++) {
                collection.add(decoder.decode(buf));
            }
            return collection;
        };
    }

    /**
     * List decoder into a fixed-size list backed by the decoded array — the mirror of
     * {@link ByteStreamEncoder#collection} for a list the caller does not have to construct.
     */
    static <B extends ByteBuf, E> StreamDecoder<B, List<E>> list(StreamDecoder<? super B, ? extends E> decoder) {
        return buf -> {
            var size = VarInts.read(buf);
            var array = new Object[size];
            for (int i = 0; i < size; i++) {
                array[i] = decoder.decode(buf);
            }
            @SuppressWarnings({"rawtypes"})
            var list = (List) Arrays.asList(array);
            return list;
        };
    }

    /**
     * Set decoder into a FastUtil reference set — reference identity, so two equal-but-distinct
     * objects both survive the round-trip.
     */
    static <B extends ByteBuf, E> StreamDecoder<B, ReferenceOpenHashSet<E>> set(StreamDecoder<? super B, ? extends E> decoder) {
        return buf -> {
            var size = VarInts.read(buf);
            var set = new ReferenceOpenHashSet<E>(size);
            for (int i = 0; i < size; i++) {
                set.add(decoder.decode(buf));
            }
            return set;
        };
    }

    /**
     * Object-array decoder: a {@code VarInt} length followed by that many elements.
     */
    static <B extends ByteBuf, E> StreamDecoder<B, E[]> array(Class<E> type, StreamDecoder<? super B, ? extends E> decoder) {
        return buf -> {
            var size = VarInts.read(buf);
            @SuppressWarnings("unchecked")
            var array = (E[]) Array.newInstance(type, size);
            for (int i = 0; i < size; i++) {
                array[i] = decoder.decode(buf);
            }
            return array;
        };
    }
}
