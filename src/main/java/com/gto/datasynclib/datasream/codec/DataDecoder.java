package com.gto.datasynclib.datasream.codec;

import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.datasream.data.ListData;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Function;
import java.util.function.IntFunction;

@FunctionalInterface
public interface DataDecoder<T> {

    T decode(@NotNull Data data, int dataVersion);

    default T decode(@NotNull Data data) {
        return decode(data, 0);
    }

    static <K, V> DataDecoder<V> convert(DataDecoder<? extends K> serializer, Function<K, V> converter) {
        return (dis, dataVersion) -> converter.apply(serializer.decode(dis, dataVersion));
    }

    static <K, V> DataDecoder<Reference2ReferenceOpenHashMap<K, V>> map(DataDecoder<? extends K> keySerializer, DataDecoder<? extends V> valueSerializer) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && list.size() > 1) {
                Reference2ReferenceOpenHashMap<K, V> map = new Reference2ReferenceOpenHashMap<>(list.size() / 2);
                for (int i = 0; i < list.size(); i++) {
                    map.put(keySerializer.decode(list.get(i++), dataVersion), valueSerializer.decode(list.get(i), dataVersion));
                }
                return map;
            }
            return new Reference2ReferenceOpenHashMap<>(1);
        };
    }

    static <K, V, M extends Map<K, V>> DataDecoder<M> map(IntFunction<M> function, DataDecoder<? extends K> keySerializer, DataDecoder<? extends V> valueSerializer) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && list.size() > 1) {
                var map = function.apply(list.size() / 2);
                for (int i = 0; i < list.size(); i++) {
                    map.put(keySerializer.decode(list.get(i++), dataVersion), valueSerializer.decode(list.get(i), dataVersion));
                }
                return map;
            }
            return function.apply(1);
        };
    }

    static <E, C extends Collection<E>> DataDecoder<C> notNullCollection(IntFunction<C> function, DataDecoder<? extends E> serializer) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && !list.isEmpty()) {
                var array = function.apply(list.size());
                list.forEach(data -> {
                    var e = serializer.decode(data, dataVersion);
                    if (e == null) return;
                    array.add(e);
                });
                return array;
            }
            return function.apply(1);
        };
    }

    static <E, C extends Collection<E>> DataDecoder<C> collection(IntFunction<C> function, DataDecoder<? extends E> serializer) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && !list.isEmpty()) {
                var array = function.apply(list.size());
                list.forEach(data -> array.add(serializer.decode(data, dataVersion)));
                return array;
            }
            return function.apply(1);
        };
    }

    static <E> DataDecoder<List<E>> list(DataDecoder<? extends E> serializer) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && !list.isEmpty()) {
                var array = new Object[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    array[i] = serializer.decode(list.get(i), dataVersion);
                }
                return (List) Arrays.asList(array);
            }
            return Collections.emptyList();
        };
    }

    static <E> DataDecoder<ReferenceOpenHashSet<E>> set(DataDecoder<? extends E> serializer) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && !list.isEmpty()) {
                var array = new ReferenceOpenHashSet<E>();
                list.forEach(data -> array.add(serializer.decode(data, dataVersion)));
                return array;
            }
            return new ReferenceOpenHashSet<>(1);
        };
    }
}
