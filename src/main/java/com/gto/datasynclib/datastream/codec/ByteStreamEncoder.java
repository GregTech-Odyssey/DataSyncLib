package com.gto.datasynclib.datastream.codec;

import io.netty.buffer.ByteBuf;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

/**
 * A {@link StreamEncoder} for a real buffer: the same write half, plus the helpers that have to write
 * something of their own — a {@code VarInt} size — and therefore need at least a {@link ByteBuf}
 * ({@link VarInts}).
 *
 * <p>{@link StreamEncoder} stays buffer-agnostic; this interface is the concrete-buffer flavour the
 * library itself uses, and a {@link ByteStreamCodec} is one of these as well as a decoder.</p>
 *
 * <p><strong>Boxing:</strong> the container helpers are generic over the element type, so primitives
 * are boxed; see {@link StreamCodec} for the full trade-off and when to hand-write instead.</p>
 *
 */
public interface ByteStreamEncoder {

    /**
     * Adapts an encoder of another type through a converter, as a {@code ByteStreamEncoder} — the
     * concrete-buffer counterpart of {@link StreamEncoder#convert}.
     */
    static <B extends ByteBuf, K, V> StreamEncoder<B, V> convert(StreamEncoder<? super B, ? super K> encoder, Function<? super V, ? extends K> converter) {
        return (buf, obj) -> encoder.encode(buf, converter.apply(obj));
    }

    /**
     * Map encoder: a {@code VarInt} size followed by the key/value pairs, in the map's iteration
     * order.
     */
    static <B extends ByteBuf, K, V> StreamEncoder<B, Map<K, V>> map(StreamEncoder<? super B, ? super K> keyEncoder, StreamEncoder<? super B, ? super V> valueEncoder) {
        return (buf, map) -> {
            VarInts.write(buf, map.size());
            map.forEach((k, v) -> {
                keyEncoder.encode(buf, k);
                valueEncoder.encode(buf, v);
            });
        };
    }

    /**
     * Collection encoder: a {@code VarInt} size followed by the elements, in the collection's
     * iteration order.
     */
    static <B extends ByteBuf, E> StreamEncoder<B, Collection<E>> collection(StreamEncoder<? super B, ? super E> encoder) {
        return (buf, collection) -> {
            VarInts.write(buf, collection.size());
            collection.forEach(element -> encoder.encode(buf, element));
        };
    }

    /**
     * Object-array encoder: a {@code VarInt} length followed by the elements. A <em>primitive</em>
     * array has its own primitive-backed codec ({@code INTS_CODEC}, {@code LONGS_CODEC}, …) and does
     * not need this.
     */
    static <B extends ByteBuf, E> StreamEncoder<B, E[]> array(StreamEncoder<? super B, ? super E> encoder) {
        return (buf, array) -> {
            VarInts.write(buf, array.length);
            for (var element : array) {
                encoder.encode(buf, element);
            }
        };
    }
}
