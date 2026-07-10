package com.gto.datasynclib.datasream;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

public abstract class AbstractDataComponentMap<K extends DataComponentKey<?>, V> extends Reference2ObjectOpenHashMap<K, V> {

    protected AbstractDataComponentMap(final int expected, final float f) {
        super(expected, f);
    }

    protected AbstractDataComponentMap(final int expected) {
        super(expected, 0.75F);
    }

    protected AbstractDataComponentMap() {
        super(16, 0.75F);
    }

    @Override
    public final V get(Object k) {
        if (k == null) return null;
        final Object[] key = this.key;
        Object curr;
        int pos;
        if ((curr = key[pos = ((DataComponentKey<?>) k).mixCode & this.mask]) == null) {
            return null;
        } else if (k == curr) {
            return this.value[pos];
        } else {
            while ((curr = key[pos = pos + 1 & this.mask]) != null) {
                if (k == curr) {
                    return this.value[pos];
                }
            }
            return null;
        }
    }

    @Override
    public final V getOrDefault(final Object k, final V defaultValue) {
        if (k == null) return defaultValue;
        final Object[] key = this.key;
        Object curr;
        int pos;
        if ((curr = key[pos = ((DataComponentKey<?>) k).mixCode & this.mask]) == null) {
            return defaultValue;
        } else if (k == curr) {
            return this.value[pos];
        } else {
            while ((curr = key[pos = pos + 1 & this.mask]) != null) {
                if (k == curr) {
                    return this.value[pos];
                }
            }
            return defaultValue;
        }
    }

    @Override
    public final V put(K k, V v) {
        if (k == null) return null;
        final Object[] key = this.key;
        int pos;
        Object curr;
        if ((curr = key[pos = k.mixCode & mask]) != null) {
            do if (curr == k) {
                final V oldValue = value[pos];
                value[pos] = v;
                return oldValue;
            }
            while ((curr = key[pos = (pos + 1) & mask]) != null);
        }
        key[pos] = k;
        value[pos] = v;
        if (size++ >= maxFill) rehash(arraySize(size + 1, f));
        return null;
    }

    @Override
    public final boolean containsKey(final Object k) {
        if (k == null) return false;
        final Object[] key = this.key;
        Object curr;
        int pos;
        if ((curr = key[pos = ((DataComponentKey<?>) k).mixCode & this.mask]) == null) {
            return false;
        } else if (k == curr) {
            return true;
        } else {
            while ((curr = key[pos = pos + 1 & this.mask]) != null) {
                if (k == curr) {
                    return true;
                }
            }
            return false;
        }
    }
}
