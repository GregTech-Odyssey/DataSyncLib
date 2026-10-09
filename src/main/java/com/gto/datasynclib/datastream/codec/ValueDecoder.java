package com.gto.datasynclib.datastream.codec;

import java.util.function.Function;

/**
 * The read half of a {@link ValueCodec}: turns a carrier produced by a {@link ValueOps} back into a
 * value.
 *
 * <p>The {@code ValueOps} counterpart of {@link StreamDecoder}. The reads it performs are the
 * unchecked {@code getXxx} ones, so a carrier of the wrong shape fails with a
 * {@link ClassCastException} rather than a diagnostic — guard with the {@code isXxx} predicates when
 * the input is not trusted.</p>
 *
 * @param <T> the type of objects to decode
 */
@FunctionalInterface
public interface ValueDecoder<T> {

    /**
     * Decodes a carrier produced by {@link ValueEncoder#encode(ValueOps, Object)} back into a value.
     */
    T decode(ValueOps ops, Object data);

    /**
     * Adapts a decoder of another type through a converter — the value the wrapped decoder reads is
     * converted to {@code T}.
     */
    static <K, T> ValueDecoder<T> convert(ValueDecoder<? extends K> decoder, Function<? super K, ? extends T> converter) {
        return (ops, data) -> converter.apply(decoder.decode(ops, data));
    }
}
