package com.gto.datasynclib.datastream.codec;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    static <K, V> ByteStreamDecoder<V> convert(ByteStreamDecoder<? extends K> decoder, Function<? super K, ? extends V> converter) {
        return dis -> converter.apply(decoder.decode(dis));
    }

    static <K, V> ByteStreamDecoder<Reference2ReferenceOpenHashMap<K, V>> map(ByteStreamDecoder<? extends K> keyDecoder, ByteStreamDecoder<? extends V> valueDecoder) {
        return dis -> {
            int size = dis.readVarInt();
            Reference2ReferenceOpenHashMap<K, V> map = new Reference2ReferenceOpenHashMap<>(size);
            for (int i = 0; i < size; i++) {
                map.put(keyDecoder.decode(dis), valueDecoder.decode(dis));
            }
            return map;
        };
    }

    static <K, V, M extends Map<K, V>> ByteStreamDecoder<M> map(IntFunction<M> function, ByteStreamDecoder<? extends K> keyDecoder, ByteStreamDecoder<? extends V> valueDecoder) {
        return dis -> {
            int size = dis.readVarInt();
            var map = function.apply(size);
            for (int i = 0; i < size; i++) {
                map.put(keyDecoder.decode(dis), valueDecoder.decode(dis));
            }
            return map;
        };
    }


    static <E, C extends Collection<E>> ByteStreamDecoder<C> collection(IntFunction<C> function, ByteStreamDecoder<? extends E> decoder) {
        return dis -> {
            int size = dis.readVarInt();
            var set = function.apply(size);
            for (int i = 0; i < size; i++) set.add(decoder.decode(dis));
            return set;
        };
    }

    static <E> ByteStreamDecoder<List<E>> list(ByteStreamDecoder<? extends E> decoder) {
        return dis -> {
            int size = dis.readVarInt();
            var array = new Object[size];
            for (int i = 0; i < size; i++) array[i] = decoder.decode(dis);
            return (List) Arrays.asList(array);
        };
    }

    static <E> ByteStreamDecoder<ReferenceOpenHashSet<E>> set(ByteStreamDecoder<? extends E> decoder) {
        return dis -> {
            int size = dis.readVarInt();
            var set = new ReferenceOpenHashSet<E>(size);
            for (int i = 0; i < size; i++) set.add(decoder.decode(dis));
            return set;
        };
    }

    static <E> ByteStreamDecoder<E[]> array(Class<E> type, ByteStreamDecoder<? extends E> decoder) {
        return dis -> {
            int size = dis.readVarInt();
            var array = (E[]) Array.newInstance(type, size);
            for (int i = 0; i < size; i++) array[i] = decoder.decode(dis);
            return array;
        };
    }
}
