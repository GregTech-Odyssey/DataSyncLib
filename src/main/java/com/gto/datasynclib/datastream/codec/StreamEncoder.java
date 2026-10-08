package com.gto.datasynclib.datastream.codec;

import java.util.function.Function;

/**
 * Encodes an object into a buffer — the write half of a {@link StreamCodec}, and the shape
 * {@code StreamCodec.of(encoder, decoder)} takes when the two halves are written separately.
 *
 * <p>The buffer type is a parameter and carries no bound: this interface never names a buffer, it only
 * converts the value on its way to one. A codec written for a broader buffer can stand in wherever a
 * narrower one is expected, which is what {@link StreamCodec#composite}'s {@code ? super B} component
 * parameters express.</p>
 *
 * <p>Everything that touches a real buffer lives in {@link ByteStreamEncoder}: the container helpers
 * write a {@code VarInt} size, and {@code StreamCodec.of} builds the codec itself.</p>
 *
 * @param <B> the buffer this encoder writes to
 * @param <T> the type of objects to encode
 */
@FunctionalInterface
public interface StreamEncoder<B, T> {

    void encode(B buf, T obj);

    /**
     * Adapts an encoder of another type through a converter — {@code V} is converted to the
     * {@code K} the wrapped encoder writes. Touches no buffer, so {@code B} stays free.
     */
    static <B, K, V> StreamEncoder<B, V> convert(StreamEncoder<? super B, ? super K> encoder, Function<? super V, ? extends K> converter) {
        return (buf, obj) -> encoder.encode(buf, converter.apply(obj));
    }
}
