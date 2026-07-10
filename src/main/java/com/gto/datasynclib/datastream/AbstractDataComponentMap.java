package com.gto.datasynclib.datastream;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

/**
 * Custom identity-based hash map extending FastUtil's {@link it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap}.
 *
 * <p>Uses {@link DataComponentKey#mixCode} and identity comparison ({@code ==}) for key lookup rather than
 * the standard {@code hashCode()}/{@code equals()} contract. This is necessary because
 * {@code DataComponentKey} instances in different registries may have the same name (and thus the same
 * {@code hashCode()}) but different identity — identity-based lookup ensures correct registry isolation.
 *
 * <p><strong>Implementation note:</strong> This class directly accesses FastUtil internal fields
 * ({@code key[]}, {@code value[]}, {@code mask}, {@code size}, {@code maxFill}) for performance,
 * bypassing the standard Map API. This is safe because the internal layout of
 * {@code Reference2ObjectOpenHashMap} is stable within a given FastUtil version.
 *
 * <p><strong>Thread-safety:</strong> This map is not thread-safe. Synchronization is the
 * responsibility of the caller (typically {@link DataComponentMap} through higher-level guards).
 *
 * @param <K> the key type (must extend {@link DataComponentKey})
 * @param <V> the value type
 */
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
