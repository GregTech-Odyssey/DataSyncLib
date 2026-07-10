package com.gto.datasynclib.util.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class ConcurrentHashMapCache<K, V> extends ConcurrentHashMap<K, V> implements MapCache<K, V> {

    protected final Function<K, V> mapFunction;

    public ConcurrentHashMapCache(Function<K, V> mapFunction) {
        this.mapFunction = mapFunction;
    }

    @Override
    public V getCache(K k) {
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
