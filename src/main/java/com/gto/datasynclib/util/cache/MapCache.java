package com.gto.datasynclib.util.cache;

/**
 * A compute-if-absent caching interface.
 */
public interface MapCache<K, V> {

    V getCache(final K k);

    V getCacheNonAtomic(final K k);

    void clear();
}
