package com.gto.datasynclib.util.cache;

public interface MapCache<K, V> {

    V getCache(final K k);

    V getCacheRecursion(final K k);

    void clear();
}
