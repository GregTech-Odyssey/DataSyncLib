package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.ListData;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

/**
 * Functional interface for encoding objects to {@link com.gto.datasynclib.datastream.data.Data} for
 * persistent storage.
 *
 * <p>Provides static helper methods to compose higher-order encoders:
 * {@link #convert} for type adaptation, {@link #map} for Map encoding (as flat interleaved key-value
 * {@link com.gto.datasynclib.datastream.data.ListData}),
 * {@link #collection} for Collection encoding, and {@link #array} for array encoding.
 *
 * @param <T> the type of objects to encode
 */
@FunctionalInterface
public interface DataEncoder<T> {

    @NotNull
    Data encode(T obj);

    static <K, V> DataEncoder<V> convert(DataEncoder<? super K> serializer, Function<V, K> converter) {
        return obj -> serializer.encode(converter.apply(obj));
    }

    static <K, V> DataEncoder<Map<? extends K, ? extends V>> map(DataEncoder<? super K> keySerializer, DataEncoder<? super V> valueSerializer) {
        return map -> {
            var data = new ListData();
            map.forEach((k, v) -> {
                data.add(keySerializer.encode(k));
                data.add(valueSerializer.encode(v));
            });
            return data;
        };
    }

    static <E> DataEncoder<Collection<? extends E>> collection(DataEncoder<? super E> serializer) {
        return list -> {
            var data = new ListData();
            list.forEach(o -> data.add(serializer.encode(o)));
            return data;
        };
    }

    static <E> DataEncoder<E[]> array(DataEncoder<? super E> serializer) {
        return list -> {
            var data = new ListData();
            for (var o : list) {
                data.add(serializer.encode(o));
            }
            return data;
        };
    }
}
