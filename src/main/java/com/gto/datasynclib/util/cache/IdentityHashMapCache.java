package com.gto.datasynclib.util.cache;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;

import java.util.function.Function;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

public class IdentityHashMapCache<K, V> extends Reference2ReferenceOpenHashMap<K, V> implements MapCache<K, V> {

    protected final Function<K, V> mapFunction;

    public IdentityHashMapCache(Function<K, V> mapFunction) {
        this.mapFunction = mapFunction;
    }

    private int find(final K k) {
        if (k == null) return containsNullKey ? n : -(n + 1);
        K curr;
        final K[] key = this.key;
        int pos;
        if ((curr = key[pos = HashCommon.mix(System.identityHashCode(k)) & mask]) == null) return -(pos + 1);
        if (k == curr) return pos;
        while (true) {
            if ((curr = key[pos = (pos + 1) & mask]) == null) return -(pos + 1);
            if (k == curr) return pos;
        }
    }

    private void insert(final int pos, final K k, final V v) {
        if (pos == n) containsNullKey = true;
        key[pos] = k;
        value[pos] = v;
        if (size++ >= maxFill) rehash(arraySize(size + 1, f));
    }

    @Override
    public V getCache(final K k) {
        final int pos = find(k);
        if (pos >= 0) return value[pos];
        final V newValue = mapFunction.apply(k);
        insert(-pos - 1, k, newValue);
        return newValue;
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
