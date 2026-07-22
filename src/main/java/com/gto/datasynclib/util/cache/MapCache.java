package com.gto.datasynclib.util.cache;

/**
 * A compute-if-absent caching interface with atomic and non-atomic retrieval options.
 *
 * <p>Implementations include:
 * <ul>
 *   <li>{@link HashMapCache} — {@link java.util.HashMap}-backed, not thread-safe</li>
 *   <li>{@link ConcurrentHashMapCache} — {@link java.util.concurrent.ConcurrentHashMap}-backed, thread-safe</li>
 *   <li>{@link IdentityHashMapCache} — identity-based (using {@code System.identityHashCode}
 *       and {@code ==}), not thread-safe</li>
 * </ul>
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface MapCache<K, V> {

    /**
     * Retrieves the cached value for the given key, computing and storing it if absent.
     * For thread-safe implementations, this operation is atomic per key.
     *
     * @param k the key
     * @return the cached or newly-computed value
     */
    V getCache(final K k);

    /**
     * Retrieves the cached value for the given key, computing and storing it if absent.
     * This variant does NOT guarantee atomicity — it trades safety for throughput
     * in single-threaded or read-heavy scenarios.
     *
     * @param k the key
     * @return the cached or newly-computed value
     */
    V getCacheNonAtomic(final K k);

    /**
     * Removes all entries from this cache.
     */
    void clear();
}
