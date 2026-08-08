package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.ListData;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Functional interface for decoding objects from {@link com.gto.datasynclib.datastream.data.Data}
 * during persistent storage loading.
 *
 * <p>The {@code dataVersion} parameter supports data format migration — decoders can inspect the
 * version to adapt their deserialization logic for older formats. The default {@link #decode(Data)}
 * method passes {@code dataVersion = 0}.
 *
 * <p>Provides static helper methods to compose higher-order decoders:
 * {@link #convert} for type adaptation, {@link #map} for Map decoding,
 * {@link #collection} for Collection decoding, {@link #list} for List decoding,
 * and {@link #set} for Set decoding.
 *
 * @param <T> the type of objects to decode
 */
@FunctionalInterface
public interface DataDecoder<T> {

    T decode(@NotNull Data data, int dataVersion);

    default T decode(@NotNull Data data) {
        return decode(data, 0);
    }

    static <K, V> DataDecoder<V> convert(DataDecoder<? extends K> decoder, Function<? super K, ? extends V> converter) {
        return (dis, dataVersion) -> converter.apply(decoder.decode(dis, dataVersion));
    }

    static <K, V> DataDecoder<Reference2ReferenceOpenHashMap<K, V>> map(DataDecoder<? extends K> keyDecoder, DataDecoder<? extends V> valueDecoder) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && list.size() > 1) {
                var size = list.size();
                Reference2ReferenceOpenHashMap<K, V> map = new Reference2ReferenceOpenHashMap<>(size / 2);
                for (int i = 0; i < size; i++) {
                    map.put(keyDecoder.decode(list.get(i++), dataVersion), valueDecoder.decode(list.get(i), dataVersion));
                }
                return map;
            }
            return new Reference2ReferenceOpenHashMap<>(1);
        };
    }

    static <K, V, M extends Map<K, V>> DataDecoder<M> map(IntFunction<M> function, DataDecoder<? extends K> keyDecoder, DataDecoder<? extends V> valueDecoder) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && list.size() > 1) {
                var size = list.size();
                var map = function.apply(size / 2);
                for (int i = 0; i < size; i++) {
                    map.put(keyDecoder.decode(list.get(i++), dataVersion), valueDecoder.decode(list.get(i), dataVersion));
                }
                return map;
            }
            return function.apply(1);
        };
    }

    static <E, C extends Collection<E>> DataDecoder<C> notNullCollection(IntFunction<C> function, DataDecoder<? extends E> decoder) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && !list.isEmpty()) {
                var array = function.apply(list.size());
                list.forEach(data -> {
                    var e = decoder.decode(data, dataVersion);
                    if (e == null) return;
                    array.add(e);
                });
                return array;
            }
            return function.apply(1);
        };
    }

    static <E, C extends Collection<E>> DataDecoder<C> collection(IntFunction<C> function, DataDecoder<? extends E> decoder) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && !list.isEmpty()) {
                var array = function.apply(list.size());
                list.forEach(data -> array.add(decoder.decode(data, dataVersion)));
                return array;
            }
            return function.apply(1);
        };
    }

    static <E> DataDecoder<List<E>> list(DataDecoder<? extends E> decoder) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && !list.isEmpty()) {
                var size = list.size();
                var array = new Object[size];
                for (int i = 0; i < size; i++) {
                    array[i] = decoder.decode(list.get(i), dataVersion);
                }
                return (List) Arrays.asList(array);
            }
            return Collections.emptyList();
        };
    }

    static <E> DataDecoder<ReferenceOpenHashSet<E>> set(DataDecoder<? extends E> decoder) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && !list.isEmpty()) {
                var array = new ReferenceOpenHashSet<E>();
                list.forEach(data -> array.add(decoder.decode(data, dataVersion)));
                return array;
            }
            return new ReferenceOpenHashSet<>(1);
        };
    }

    static <E> DataDecoder<E[]> array(Class<E> type, DataDecoder<? extends E> decoder) {
        return (dis, dataVersion) -> {
            if (dis instanceof ListData(List<Data> list) && !list.isEmpty()) {
                var size = list.size();
                var array = (E[]) Array.newInstance(type, size);
                for (int i = 0; i < size; i++) {
                    array[i] = decoder.decode(list.get(i), dataVersion);
                }

            }
            return (E[]) Array.newInstance(type, 0);
        };
    }
}
