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
 * <p>The {@link #frozen} flag is {@code volatile}. Registration and freeze/unfreeze
 * operations are {@code synchronized}. Read operations ({@code get()}, {@code getId()},
 * iteration) are lock-free and safe after freezing.</p>
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

    public Registry(String name, DataCodec<K> keyCodec, Function<? super V, ? extends K> keyGetter, @Nullable Class<V> valueType) {
        this.name = Objects.requireNonNull(name, "name");
        this.keyGetter = Objects.requireNonNull(keyGetter, "keyGetter");
        Objects.requireNonNull(keyCodec, "keyCodec");
        this.valueType = valueType;
        this.dataCodec = DataCodec.of(
                obj -> keyCodec.encode(keyGetter.apply(obj)),
                (data, dataVersion) -> keyValues.get(keyCodec.decode(data, dataVersion)));
    }

    public Registry(String name, DataCodec<K> keyCodec, @Nullable Class<V> valueType) {
        this.name = Objects.requireNonNull(name, "name");
        this.keyGetter = this::getKeyByMap;
        Objects.requireNonNull(keyCodec, "keyCodec");
        this.valueType = valueType;
        this.dataCodec = DataCodec.of(
                obj -> keyCodec.encode(this.getKeyByMap(obj)),
                (data, dataVersion) -> keyValues.get(keyCodec.decode(data, dataVersion)));
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

    public <T extends V> T register(K key, T value) {
        synchronized (this) {
            if (frozen) throw new IllegalStateException("Registry %s has been frozen".formatted(name));
            if (keyValues.put(key, value) != null)
                throw new IllegalStateException("Registry %s contains key %s already".formatted(name, key));
        }
        return value;
    }

    public <T extends V> T replace(K key, T value) {
        synchronized (this) {
            if (frozen) throw new IllegalStateException("Registry %s has been frozen".formatted(name));
            if (keyValues.put(key, value) == null) {
                throw new IllegalStateException("Couldn't find key %s in registry %s".formatted(name, key));
            }
        }
        return value;
    }

    public final boolean containsKey(K key) {
        return keyValues.containsKey(key);
    }

    public final boolean containsValue(V value) {
        return valueKeys.containsKey(value);
    }

    public final V get(int id) {
        return idValues.get(id);
    }

    @Nullable
    public final V get(K key) {
        return keyValues.get(key);
    }

    public final V getOrDefault(K key, V defaultValue) {
        return keyValues.getOrDefault(key, defaultValue);
    }

    public final int getId(V value) {
        var entry = valueKeys.get(value);
        if (entry == null) throw new NullPointerException("Value not found in registry: " + value);
        return entry.priority;
    }

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

    public Set<V> values() {
        return valueKeys.keySet();
    }

    public Set<K> keys() {
        return keyValues.keySet();
    }

    @Override
    public @NotNull Iterator<V> iterator() {
        return valueKeys.keySet().iterator();
    }

    @Override
    public void forEach(Consumer<? super V> action) {
        valueKeys.keySet().forEach(action);
    }

    @Override
    public Spliterator<V> spliterator() {
        return valueKeys.keySet().spliterator();
    }

    public void forEachKey(Consumer<? super K> action) {
        keyValues.keySet().forEach(action);
    }

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
    public DataCodec<V> dataCodec() {
        return dataCodec;
    }

    public final ByteStreamCodec<V> streamCodec() {
        return streamCodec;
    }

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
