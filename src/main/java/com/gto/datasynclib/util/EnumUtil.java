package com.gto.datasynclib.util;

import com.gto.datasynclib.util.cache.ConcurrentHashMapCache;
import lombok.experimental.UtilityClass;
import net.minecraft.util.StringRepresentable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Predicate;

/**
 * Enum serialization helpers with per-type caching.
 *
 * <p>Two name spaces exist and they are <strong>not</strong> interchangeable:
 * {@link #getEnum(Class, String)} resolves by {@link Enum#name()} (the constant identifier),
 * while {@link #getSerializedEnum(Class, String)} resolves by
 * {@link #getSerializedName(Enum)}, i.e. {@link StringRepresentable#getSerializedName()} when
 * the enum implements it, otherwise {@code name()}. Both return {@code null} for an unknown
 * name; the caches are built once per enum type.</p>
 *
 * <p><strong>Fixed enums</strong> are types whose constant set is guaranteed not to change.
 * The framework persists those by ordinal (compact); all other enums are persisted by name so
 * that inserting a constant cannot silently reinterpret existing saves. Register them through
 * {@link #addFixedEnum(Class)} or {@link #addFixedEnum(Predicate)}.</p>
 */
@UtilityClass
public class EnumUtil {

    private final ArrayList<Predicate<Class<? extends Enum<?>>>> FIXED_ENUM_PREDICATES = new ArrayList<>();

    private final ConcurrentHashMapCache<Class<? extends Enum<?>>, Boolean> FIXED_ENUM_NAME_CACHE = new ConcurrentHashMapCache<>(t -> {
        for (var p : FIXED_ENUM_PREDICATES) {
            if (p.test(t)) {
                return true;
            }
        }
        return false;
    });

    private final ConcurrentHashMapCache<Class<? extends Enum<?>>, HashMap<String, Enum<?>>> SERIALIZED_NAME_CACHE = new ConcurrentHashMapCache<>(t -> {
        var map = new HashMap<String, Enum<?>>();
        for (var value : t.getEnumConstants()) {
            map.put(getSerializedName(value), value);
        }
        return map;
    });
    private final ConcurrentHashMapCache<Class<? extends Enum<?>>, HashMap<String, Enum<?>>> NAME_CACHE = new ConcurrentHashMapCache<>(t -> {
        var map = new HashMap<String, Enum<?>>();
        for (var value : t.getEnumConstants()) {
            map.put(value.name(), value);
        }
        return map;
    });

    /**
     * Marks an enum type as "fixed" — it will be persisted by ordinal, so its constant order
     * must never change. Use only for enums you control.
     */
    public void addFixedEnum(Class<? extends Enum<?>> type) {
        FIXED_ENUM_NAME_CACHE.put(type, true);
    }

    /**
     * Marks every enum type matching the predicate as "fixed" (ordinal persistence).
     */
    public void addFixedEnum(Predicate<Class<? extends Enum<?>>> predicate) {
        synchronized (FIXED_ENUM_PREDICATES) {
            FIXED_ENUM_PREDICATES.add(predicate);
        }
    }

    /**
     * @return whether {@code type} is persisted by ordinal rather than by name
     */
    public boolean isFixed(Class<? extends Enum<?>> type) {
        return FIXED_ENUM_NAME_CACHE.getCache(type);
    }

    /**
     * Resolves a constant by its {@link Enum#name()}.
     *
     * @return the matching constant, or {@code null} if the name is unknown
     */
    public <T extends Enum<T>> T getEnum(Class<T> type, String name) {
        return (T) NAME_CACHE.getCache(type).get(name);
    }

    /**
     * @return {@link StringRepresentable#getSerializedName()} when the enum implements it,
     * otherwise {@link Enum#name()}
     */
    public String getSerializedName(Enum<?> enumValue) {
        if (enumValue instanceof StringRepresentable provider) {
            return provider.getSerializedName();
        } else {
            return enumValue.name();
        }
    }

    /**
     * Resolves a constant by {@link #getSerializedName(Enum)}.
     *
     * @return the matching constant, or {@code null} if the name is unknown
     */
    public <T extends Enum<T>> T getSerializedEnum(Class<T> type, String name) {
        return (T) SERIALIZED_NAME_CACHE.getCache(type).get(name);
    }
}
