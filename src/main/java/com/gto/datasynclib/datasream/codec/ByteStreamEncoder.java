package com.gto.datasynclib.datasream.codec;

import net.minecraft.network.FriendlyByteBuf;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

@FunctionalInterface
public interface ByteStreamEncoder<T> {

    void encode(FriendlyByteBuf buf, T obj);

    static <K, V> ByteStreamEncoder<V> convert(ByteStreamEncoder<K> serializer, Function<V, K> converter) {
        return (buf, obj) -> serializer.encode(buf, converter.apply(obj));
    }

    static <K, V> ByteStreamEncoder<Map<K, V>> map(ByteStreamEncoder<K> keySerializer, ByteStreamEncoder<V> valueSerializer) {
        return (dos, map) -> {
            dos.writeVarInt(map.size());
            map.forEach((k, v) -> {
                keySerializer.encode(dos, k);
                valueSerializer.encode(dos, v);

            });
        };
    }

    static <E> ByteStreamEncoder<Collection<E>> collection(ByteStreamEncoder<E> serializer) {
        return (dos, list) -> {
            dos.writeVarInt(list.size());
            list.forEach(o -> serializer.encode(dos, o));
        };
    }

    static <E> ByteStreamEncoder<E[]> array(ByteStreamEncoder<E> serializer) {
        return (dos, list) -> {
            dos.writeVarInt(list.length);
            for (E o : list) {
                serializer.encode(dos, o);
            }
        };
    }
}
