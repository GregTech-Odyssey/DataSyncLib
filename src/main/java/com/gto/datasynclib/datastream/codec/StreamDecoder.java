package com.gto.datasynclib.datastream.codec;

import java.util.function.Function;

/**
 * Decodes an object from a buffer — the read half of a {@link StreamCodec}, and the shape
 * {@code StreamCodec.of(encoder, decoder)} takes when the two halves are written separately.
 *
 * <p>The buffer type is a parameter and carries no bound: this interface never names a buffer, it only
 * converts what a buffer produced. Container sizes come from the stream, so malformed input can request
 * a large allocation — validate a size before allocating when the input is not trusted.</p>
 *
 * <p>Everything that touches a real buffer lives in {@link ByteStreamDecoder}: the container helpers
 * read a {@code VarInt} size, and {@code StreamCodec.of} builds the codec itself.</p>
 *
 * @param <B> the buffer this decoder reads from
 * @param <T> the type of objects to decode
 */
@FunctionalInterface
public interface StreamDecoder<B, T> {

    T decode(B buf);

    /**
     * Adapts a decoder of another type through a converter — the value the wrapped decoder reads is
     * converted to {@code V}. Touches no buffer, so {@code B} stays free.
     */
    static <B, K, V> StreamDecoder<B, V> convert(StreamDecoder<? super B, ? extends K> decoder, Function<? super K, ? extends V> converter) {
        return buf -> converter.apply(decoder.decode(buf));
    }
}
