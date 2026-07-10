package com.gto.datasynclib.datastream.codec;

import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Functional interface for decoding objects from a {@link FriendlyByteBuf} (network deserialization).
 *
 * <p>Provides static helper methods to compose higher-order decoders:
 * {@link #convert} for type adaptation, {@link #map} for Map decoding (defaults to
 * {@link it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap}),
 * {@link #list} for List decoding, and {@link #set} for Set decoding.
 *
 * @param <T> the type of objects to decode
 */
@FunctionalInterface
public interface ByteStreamDecoder<T> {

    T decode(FriendlyByteBuf buf);

    static <K, V> ByteStreamDecoder<V> convert(ByteStreamDecoder<K> serializer, Function<K, V> converter) {
        return dis -> converter.apply(serializer.decode(dis));
    }

    static <K, V> ByteStreamDecoder<Reference2ReferenceOpenHashMap<K, V>> map(ByteStreamDecoder<K> keySerializer, ByteStreamDecoder<V> valueSerializer) {
        return dis -> {
            int size = dis.readVarInt();
            Reference2ReferenceOpenHashMap<K, V> map = new Reference2ReferenceOpenHashMap<>(size);
            for (int i = 0; i < size; i++) {
                map.put(keySerializer.decode(dis), valueSerializer.decode(dis));
            }
            return map;
        };
    }

    static <K, V, M extends Map<K, V>> ByteStreamDecoder<M> map(IntFunction<M> function, ByteStreamDecoder<K> keySerializer, ByteStreamDecoder<V> valueSerializer) {
        return dis -> {
            int size = dis.readVarInt();
            var map = function.apply(size);
            for (int i = 0; i < size; i++) {
                map.put(keySerializer.decode(dis), valueSerializer.decode(dis));
            }
            return map;
        };
    }

    static <E> ByteStreamDecoder<List<E>> list(ByteStreamDecoder<E> serializer) {
        return dis -> {
            int size = dis.readVarInt();
            var array = new Object[size];
            for (int i = 0; i < size; i++) array[i] = serializer.decode(dis);
            return (List) Arrays.asList(array);
        };
    }

    static <E, L extends List<E>> ByteStreamDecoder<L> list(IntFunction<L> function, ByteStreamDecoder<E> serializer) {
        return dis -> {
            int size = dis.readVarInt();
            var list = function.apply(size);
            for (int i = 0; i < size; i++) list.add(serializer.decode(dis));
            return list;
        };
    }

    static <E> ByteStreamDecoder<ReferenceOpenHashSet<E>> set(ByteStreamDecoder<E> serializer) {
        return dis -> {
            int size = dis.readVarInt();
            var set = new ReferenceOpenHashSet<E>(size);
            for (int i = 0; i < size; i++) set.add(serializer.decode(dis));
            return set;
        };
    }

    static <E, S extends Set<E>> ByteStreamDecoder<S> set(Int2ObjectFunction<S> function, ByteStreamDecoder<E> serializer) {
        return dis -> {
            int size = dis.readVarInt();
            var set = function.apply(size);
            for (int i = 0; i < size; i++) set.add(serializer.decode(dis));
            return set;
        };
    }
}
