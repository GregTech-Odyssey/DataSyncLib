package com.gto.datasynclib.util.cache;

import java.util.HashMap;
import java.util.function.Function;

public class HashMapCache<K, V> extends HashMap<K, V> implements MapCache<K, V> {

    protected final Function<K, V> mapFunction;

    public HashMapCache(Function<K, V> mapFunction) {
        this.mapFunction = mapFunction;
    }

    @Override
    public V getCache(final K k) {
        return super.computeIfAbsent(k, mapFunction);
    }

    @Override
    public V getCacheRecursion(K k) {
        var v = super.get(k);
        if (v != null) return v;
        v = mapFunction.apply(k);
        super.put(k, v);
        return v;
    }
}
