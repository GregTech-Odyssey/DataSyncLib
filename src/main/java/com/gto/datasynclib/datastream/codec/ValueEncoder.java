package com.gto.datasynclib.datastream.codec;

import java.util.function.Function;

/**
 * The write half of a {@link ValueCodec}: turns a value into a carrier built by a {@link ValueOps}.
 *
 * <p>The {@code ValueOps} counterpart of {@link StreamEncoder}: this interface never names a carrier,
 * it only turns a value into one, so a codec written for a broader carrier can stand in wherever a
 * narrower one is expected. {@link ValueCodec#of(ValueEncoder, ValueDecoder)} pairs this half with a
 * {@link ValueDecoder}, and a component that only ever writes can depend on this interface alone.</p>
 *
 * @param <T> the type of objects to encode
 */
@FunctionalInterface
public interface ValueEncoder<T> {

    /**
     * Encodes {@code value} into a carrier of {@code ops}.
     */
    Object encode(ValueOps ops, T value);

    /**
     * Adapts an encoder of another type through a converter — {@code U} is converted to the
     * {@code K} the wrapped encoder writes. Touches no carrier on its own account, so {@code V} and
     * the carrier stays free.
     */
    static <K, T> ValueEncoder<T> convert(ValueEncoder<? super K> encoder, Function<? super T, ? extends K> converter) {
        return (ops, value) -> encoder.encode(ops, converter.apply(value));
    }
}
