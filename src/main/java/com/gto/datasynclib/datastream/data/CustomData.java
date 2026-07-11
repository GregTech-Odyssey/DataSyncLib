package com.gto.datasynclib.datastream.data;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Extensible Data type for custom serialization formats. Wire format ID: 15.
 * Uses a global registry keyed by integer ID.
 * Custom types are registered automatically via the inner Type record.
 */
public final class CustomData<T> implements Data {

    private static final Int2ObjectOpenHashMap<Type<?>> REGISTRY = new Int2ObjectOpenHashMap<>();

    public static synchronized void register(Type<?> type) {
        if (REGISTRY.put(type.id, type) != null)
            throw new IllegalArgumentException("Custom data type id " + type.id + " is already registered");
    }

    private CustomData(Type<T> type, T data) {
        this.type = type;
        this.data = data;
    }

    @SuppressWarnings("all")
    public static CustomData<?> read(int id, ByteBuf stream) {
        Type type = REGISTRY.get(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown custom data type id: " + id);
        }
        return type.create(type.readFunction.apply(stream));
    }

    private final Type<T> type;
    private T data;

    public T get() {
        return data;
    }

    public void set(T data) {
        this.data = data;
    }

    @Override
    public void write(ByteBuf stream) {
        Data.writeVarInt(stream, type.id);
        type.writeConsumer.accept(data, stream);
    }

    @Override
    public byte getId() {
        return Data.CUSTOM;
    }

    @Override
    public Data copy() {
        return type.copyFunction.apply(type, this);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof CustomData<?> customData && (this.data == customData.data || this.data.equals(customData.data)));
    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    @Override
    public String toString() {
        return data.toString();
    }

    @Override
    public <TYPE> CustomData<TYPE> toCustomData(CustomData.Type<TYPE> type) {
        if (this.type == type) return (CustomData<TYPE>) this;
        return null;
    }

    public record Type<T>(int id, BiFunction<Type<T>, CustomData<T>, CustomData<T>> copyFunction,
                          BiConsumer<T, ByteBuf> writeConsumer,
                          Function<ByteBuf, T> readFunction) {

        public static <T> Builder<T> builder(int id) {
            return new Builder<>(id);
        }

        public static <T> Builder<T> builder(String id) {
            return new Builder<>(id.hashCode());
        }

        public CustomData<T> create(@NotNull T data) {
            return new CustomData<>(this, data);
        }

        public static final class Builder<T> {

            private final int id;
            private BiFunction<Type<T>, CustomData<T>, CustomData<T>> copy;
            @Setter
            @Accessors(fluent = true, chain = true)
            private BiConsumer<T, ByteBuf> write;
            @Setter
            @Accessors(fluent = true, chain = true)
            private Function<ByteBuf, T> read;

            private Builder(int id) {
                this.id = id;
            }

            public Builder<T> copy(Function<T, T> copyFunction) {
                this.copy = (t, d) -> t.create(copyFunction.apply(d.data));
                return this;
            }

            public Type<T> build() {
                var type = new Type<>(id, copy == null ? (t, d) -> d : copy, write, read);
                register(type);
                return type;
            }
        }
    }
}
