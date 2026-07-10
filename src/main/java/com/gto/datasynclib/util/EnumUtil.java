package com.gto.datasynclib.util;

import com.gto.datasynclib.util.cache.ConcurrentHashMapCache;
import lombok.experimental.UtilityClass;
import net.minecraft.util.StringRepresentable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Predicate;

@UtilityClass
public class EnumUtil {

    private final ArrayList<Predicate<Class<? extends Enum<?>>>> FIXED_ENUM_PREDICATES = new ArrayList<>();

    private final ConcurrentHashMapCache<Class<? extends Enum<?>>, Boolean> FIXED_ENUM_CACHE = new ConcurrentHashMapCache<>(t -> {
        for (var p : FIXED_ENUM_PREDICATES) {
            if (p.test(t)) {
                return true;
            }
        }
        return false;
    });

    private final ConcurrentHashMapCache<Class<? extends Enum<?>>, HashMap<String, Enum<?>>> SERIALIZED_CACHE = new ConcurrentHashMapCache<>(t -> {
        var map = new HashMap<String, Enum<?>>();
        for (var value : t.getEnumConstants()) {
            map.put(getSerializedName(value), value);
        }
        return map;
    });
    private final ConcurrentHashMapCache<Class<? extends Enum<?>>, HashMap<String, Enum<?>>> CACHE = new ConcurrentHashMapCache<>(t -> {
        var map = new HashMap<String, Enum<?>>();
        for (var value : t.getEnumConstants()) {
            map.put(getName(value), value);
        }
        return map;
    });

    public void addFixedEnum(Class<? extends Enum<?>> type) {
        FIXED_ENUM_CACHE.put(type, true);
    }

    public void addFixedEnum(Predicate<Class<? extends Enum<?>>> predicate) {
        synchronized (FIXED_ENUM_PREDICATES) {
            FIXED_ENUM_PREDICATES.add(predicate);
        }
    }

    public boolean isFixed(Class<? extends Enum<?>> type) {
        return FIXED_ENUM_CACHE.getCache(type);
    }

    public String getName(Enum<?> enumValue) {
        if (enumValue instanceof StringRepresentable provider) {
            return provider.getSerializedName();
        } else {
            return enumValue.name();
        }
    }

    public <T extends Enum<T>> T getEnum(Class<T> type, String name) {
        return (T) CACHE.getCache(type).get(name);
    }

    public String getSerializedName(Enum<?> enumValue) {
        if (enumValue instanceof StringRepresentable provider) {
            return provider.getSerializedName();
        } else {
            return enumValue.name();
        }
    }

    public <T extends Enum<T>> T getSerializedEnum(Class<T> type, String name) {
        return (T) SERIALIZED_CACHE.getCache(type).get(name);
    }
}
