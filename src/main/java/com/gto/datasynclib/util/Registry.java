package com.gto.datasynclib.util;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.datastream.codec.ByteStreamCodec;
import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.util.holder.IntObjectHolder;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Generic named registry mapping {@link Comparable} keys to values with automatic
 * integer ID assignment for efficient network serialization.
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>{@link #unfreeze()} — clears the registry and opens it for registration</li>
 *   <li>{@link #register(Comparable, Object)} — add entries while unfrozen</li>
 *   <li>{@link #freeze()} — sorts entries by key, assigns sequential integer IDs, locks registry</li>
 * </ol>
 *
 * <h3>Serialization:</h3>
 * <ul>
 *   <li>{@link #streamCodec()} — encodes/decodes by integer ID (compact, for network)</li>
 *   <li>{@link #dataCodec()} — encodes/decodes by key (self-describing, for disk persistence).
 *       The key {@link DataCodec} is provided at construction time and the resulting
 *       value codec is computed once and cached.</li>
 *   <li>{@link #codec(com.mojang.serialization.Codec)} — Mojang DFU codec via key xmap</li>
 * </ul>
 *
 * <h3>Thread-safety:</h3>
 * <p>The {@link #frozen} flag is {@code volatile}. {@link #register(Comparable, Object)} and
 * {@link #replace(Comparable, Object)} synchronize on {@code this}; {@link #freeze()} and
 * {@link #unfreeze()} do <em>not</em> — call them from the owning thread (normally mod
 * construction). Read operations ({@code get()}, {@code getId()}, iteration) are lock-free
 * and safe after freezing.</p>
 *
 * @param <K> the key type (must be {@link Comparable})
 * @param <V> the value type
 * @see com.gto.datasynclib.datastream.DataComponentRegistry
 */
public class Registry<K extends Comparable<K>, V> implements Iterable<V> {

    @Getter
    protected final String name;
    protected final LinkedHashMap<K, V> keyValues = new LinkedHashMap<>();
    protected final IdMap<V, K> valueKeys = new IdMap<>();
    protected final ReferenceArrayList<V> idValues = new ReferenceArrayList<>();
    protected final ByteStreamCodec<V> streamCodec = ByteStreamCodec.of((buf, obj) -> buf.writeVarInt(valueKeys.get(obj).priority), buf -> idValues.get(buf.readVarInt()));

    @Getter
    protected volatile boolean frozen = true;

    /**
     * Resolves a registered value to its registry key. Defaults to a reverse lookup through
     * {@link #valueKeys}; subclasses may supply a custom {@link Function} at construction
     * (e.g. reading a key field directly from the value) to avoid the map lookup.
     */
    protected final Function<? super V, ? extends K> keyGetter;

    /**
     * Cached {@link DataCodec} for this registry's <em>values</em>, encoding by key via
     * {@link #keyGetter}. Built once at construction time and reused by {@link #dataCodec()}.
     */
    protected final DataCodec<V> dataCodec;

    protected final DataSyncCodec<V> combinedCodec;

    /**
     * The concrete runtime class of this registry's values ({@code V}), when known.
     * When non-{@code null} and {@link #freeze()} completes, this registry's
     * {@link #streamCodec()} + {@link #dataCodec()} are <em>automatically registered</em>
     * into the global {@link DataSyncCodec} under this type, so any field of type
     * {@code V} can be serialized without manual codec registration.
     */
    @Nullable
    protected final Class<V> valueType;

    /**
     * Builds the global {@link DataSyncCodec} bridging this registry's
     * {@link #streamCodec()} and {@link #dataCodec()}.
     */
    public final void registerToGlobalCodecs() {
        if (valueType == null) return;
        DataSyncCodec.register(valueType, streamCodec(), dataCodec());
    }

    public Registry(String name, DataCodec<K> keyCodec, Function<? super V, ? extends K> keyGetter) {
        this(name, keyCodec, keyGetter, null);
    }

    public Registry(String name, DataCodec<K> keyCodec, @Nullable Function<? super V, ? extends K> keyGetter, @Nullable Class<V> valueType) {
        this.name = Objects.requireNonNull(name, "name");
        this.keyGetter = keyGetter == null ? this::getKeyByMap : keyGetter;
        Objects.requireNonNull(keyCodec, "keyCodec");
        this.valueType = valueType;
        this.dataCodec = DataCodec.of(
                obj -> keyCodec.encode(this.keyGetter.apply(obj)),
                (data, dataVersion) -> keyValues.get(keyCodec.decode(data, dataVersion)));
        this.combinedCodec = DataSyncCodec.of(streamCodec, dataCodec);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isContextValid() {
        return true;
    }

    public void unfreeze() {
        if (!frozen) throw new IllegalStateException("Registry %s is already unfrozen!".formatted(name));
        if (!isContextValid())
            throw new IllegalStateException("Registry %s cannot be set to unfrozen state in current context!".formatted(name));
        clear();
        this.frozen = false;
    }

    public void freeze() {
        if (frozen) throw new IllegalStateException("Registry %s is already frozen!".formatted(name));
        if (!isContextValid())
            throw new IllegalStateException("Registry %s cannot be set to frozen state in current context!".formatted(name));
        frozen = true;
        build();
        // Auto-register this registry's stream + data codecs into the global DataSyncCodec
        // when the value runtime type is known, so fields of type V serialize via this registry.
        if (valueType != null) registerToGlobalCodecs();
    }

    protected void clear() {
        keyValues.clear();
        valueKeys.clear();
        idValues.clear();
    }

    protected void build() {
        var list = new ArrayList<Map.Entry<K, V>>(keyValues.size());
        list.addAll(keyValues.entrySet());
        list.sort(Map.Entry.comparingByKey());
        var size = list.size();
        idValues.size(size);
        valueKeys.size(size);
        for (int i = 0; i < size; i++) {
            var e = list.get(i);
            var v = e.getValue();
            valueKeys.put(v, new IntObjectHolder<>(i, e.getKey()));
            idValues.set(i, v);
        }
    }

    /**
     * Registers a value under the given key.
     *
     * @return the registered value
     * @throws IllegalStateException if the registry is frozen or the key is already present
     */
    public <T extends V> T register(K key, T value) {
        synchronized (this) {
            if (frozen) throw new IllegalStateException("Registry %s has been frozen".formatted(name));
            if (keyValues.put(key, value) != null)
                throw new IllegalStateException("Registry %s contains key %s already".formatted(name, key));
        }
        return value;
    }

    /**
     * Replaces the value already registered under the given key.
     *
     * @return the replacement value
     * @throws IllegalStateException if the registry is frozen or the key is not registered
     */
    public <T extends V> T replace(K key, T value) {
        synchronized (this) {
            if (frozen) throw new IllegalStateException("Registry %s has been frozen".formatted(name));
            if (keyValues.put(key, value) == null) {
                throw new IllegalStateException("Couldn't find key %s in registry %s".formatted(name, key));
            }
        }
        return value;
    }

    /**
     * @return whether a value is registered under {@code key}
     */
    public final boolean containsKey(K key) {
        return keyValues.containsKey(key);
    }

    /**
     * @return whether {@code value} is registered (identity-based lookup)
     */
    public final boolean containsValue(V value) {
        return valueKeys.containsKey(value);
    }

    /**
     * Looks a value up by its stable integer id. Ids are assigned by {@link #freeze()}, which
     * numbers the entries in key order — the same id therefore denotes the same entry on both
     * sides as long as the registered keys match.
     *
     * @throws IndexOutOfBoundsException if no value has that id
     */
    public final V get(int id) {
        return idValues.get(id);
    }

    /**
     * @return the value registered under {@code key}, or {@code null} if absent
     */
    @Nullable
    public final V get(K key) {
        return keyValues.get(key);
    }

    /**
     * @return the value registered under {@code key}, or {@code defaultValue} if absent
     */
    public final V getOrDefault(K key, V defaultValue) {
        return keyValues.getOrDefault(key, defaultValue);
    }

    /**
     * @return the stable integer id of {@code value}
     * @throws NullPointerException if the value is not part of this registry
     */
    public final int getId(V value) {
        var entry = valueKeys.get(value);
        if (entry == null) throw new NullPointerException("Value not found in registry: " + value);
        return entry.priority;
    }

    /**
     * Derives the registry key of a value through {@code keyGetter} — by default a reverse
     * lookup in the value map.
     */
    public final K getKey(V value) {
        return keyGetter.apply(value);
    }

    /**
     * Default {@link #keyGetter}: resolves the key by reverse lookup in {@link #valueKeys}.
     */
    protected final K getKeyByMap(V value) {
        var entry = valueKeys.get(value);
        if (entry == null) throw new NullPointerException("Value not found in registry: " + value);
        return entry.value;
    }

    /**
     * @return the registered values, in id order (a live view)
     */
    public Set<V> values() {
        return valueKeys.keySet();
    }

    /**
     * @return the registered keys, in insertion order (a live view)
     */
    public Set<K> keys() {
        return keyValues.keySet();
    }

    /**
     * Iterates the registered values in id order.
     */
    @Override
    public @NotNull Iterator<V> iterator() {
        return valueKeys.keySet().iterator();
    }

    /**
     * Iterates the registered values in id order.
     */
    @Override
    public void forEach(Consumer<? super V> action) {
        valueKeys.keySet().forEach(action);
    }

    @Override
    public Spliterator<V> spliterator() {
        return valueKeys.keySet().spliterator();
    }

    /**
     * Iterates the registered keys in insertion order.
     */
    public void forEachKey(Consumer<? super K> action) {
        keyValues.keySet().forEach(action);
    }

    /**
     * Iterates key/value pairs in insertion order.
     */
    public void forEachKeyValue(BiConsumer<? super K, ? super V> action) {
        keyValues.forEach(action);
    }

    /**
     * Returns the cached {@link DataCodec} built at construction time, encoding/decoding by key
     * via {@link #keyGetter}. Values are encoded as their registry key (self-describing, for disk
     * persistence); decoding looks the value back up by key from {@link #keyValues}.
     *
     * @return the cached value-level {@link DataCodec}
     */
    public final DataCodec<V> dataCodec() {
        return dataCodec;
    }

    /**
     * @return the cached network codec: values are written as their stable VarInt id, so this
     * form is compact but only valid between peers whose registries match
     */
    public final ByteStreamCodec<V> streamCodec() {
        return streamCodec;
    }

    /**
     * @return the combined (stream + data) codec, created once at construction time; equals
     * {@link DataSyncCodec#of(ByteStreamCodec, DataCodec)} of {@link #streamCodec()} and
     * {@link #dataCodec()}
     */
    public final DataSyncCodec<V> combinedCodec() {
        return combinedCodec;
    }

    /**
     * Builds a Mojang DFU codec that maps registry keys to values in both directions.
     *
     * @param keyCodec the codec used for the key, e.g. {@code Codec.STRING}
     */
    public Codec<V> codec(Codec<K> keyCodec) {
        return keyCodec.xmap(keyValues::get, this::getKey);
    }

    public enum Phase {
        /**
         * Registration and Modification is not started
         */
        PRE,
        /**
         * Registration and Modification is available
         */
        OPEN,
        /**
         * Registration is unavailable and only Modification is available
         */
        CLOSED,
        /**
         * Registration and Modification is unavailable
         */
        FROZEN
    }

    protected static final class IdMap<K, V> extends Reference2ReferenceLinkedOpenHashMap<K, IntObjectHolder<V>> {

        protected void size(final int size) {
            final int needed = (int) Math.min(1 << 30, Math.max(2, HashCommon.nextPowerOfTwo((long) Math.ceil(size / f))));
            if (needed > n) rehash(needed);
        }
    }
}
