package com.gto.datasynclib.datastream.codec;

import net.minecraft.network.FriendlyByteBuf;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

/**
 * Functional interface for encoding objects to a {@link FriendlyByteBuf} for network transmission.
 *
 * <p>Provides static helper methods to compose higher-order encoders:
 * {@link #convert} for type adaptation, {@link #map} for Map encoding,
 * {@link #collection} for Collection encoding, and {@link #array} for array encoding.
 *
 * @param <T> the type of objects to encode
 */
@FunctionalInterface
public interface ByteStreamEncoder<T> {

    void encode(FriendlyByteBuf buf, T obj);

    static <K, V> ByteStreamEncoder<V> convert(ByteStreamEncoder<? super K> encoder, Function<? super V, ? extends K> converter) {
        return (buf, obj) -> encoder.encode(buf, converter.apply(obj));
    }

    static <K, V> ByteStreamEncoder<Map<K, V>> map(ByteStreamEncoder<? super K> keyEncoder, ByteStreamEncoder<? super V> valueEncoder) {
        return (dos, map) -> {
            dos.writeVarInt(map.size());
            map.forEach((k, v) -> {
                keyEncoder.encode(dos, k);
                valueEncoder.encode(dos, v);

            });
        };
    }

    static <E> ByteStreamEncoder<Collection<E>> collection(ByteStreamEncoder<? super E> encoder) {
        return (dos, list) -> {
            dos.writeVarInt(list.size());
            list.forEach(o -> encoder.encode(dos, o));
        };
    }

    static <E> ByteStreamEncoder<E[]> array(ByteStreamEncoder<? super E> encoder) {
        return (dos, list) -> {
            dos.writeVarInt(list.length);
            for (E o : list) {
                encoder.encode(dos, o);
            }
        };
    }
}
