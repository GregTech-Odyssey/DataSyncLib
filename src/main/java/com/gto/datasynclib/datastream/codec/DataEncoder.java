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

    static <K, V> DataEncoder<V> convert(DataEncoder<? super K> encoder, Function<? super V, ? extends K> converter) {
        return obj -> encoder.encode(converter.apply(obj));
    }

    static <K, V> DataEncoder<Map<? extends K, ? extends V>> map(DataEncoder<? super K> keyEncoder, DataEncoder<? super V> valueEncoder) {
        return map -> {
            var data = new ListData();
            map.forEach((k, v) -> {
                data.add(keyEncoder.encode(k));
                data.add(valueEncoder.encode(v));
            });
            return data;
        };
    }

    static <E> DataEncoder<Collection<? extends E>> collection(DataEncoder<? super E> encoder) {
        return list -> {
            var data = new ListData();
            list.forEach(o -> data.add(encoder.encode(o)));
            return data;
        };
    }

    static <E> DataEncoder<E[]> array(DataEncoder<? super E> encoder) {
        return list -> {
            var data = new ListData();
            for (var o : list) {
                data.add(encoder.encode(o));
            }
            return data;
        };
    }
}
