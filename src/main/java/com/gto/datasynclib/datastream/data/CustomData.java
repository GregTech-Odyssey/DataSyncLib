package com.gto.datasynclib.datastream.data;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;
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
        return type.copyFunction.apply(this);
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

    public record Type<T>(int id, Function<CustomData<T>, CustomData<T>> copyFunction,
                          BiConsumer<T, ByteBuf> writeConsumer,
                          Function<ByteBuf, T> readFunction) {

        public Type {
            register(this);
        }

        public static <T> Builder<T> builder() {
            return new Builder<>();
        }

        public static <T> Builder<T> builder(Class<T> type) {
            return new Builder<>();
        }

        public CustomData<T> create(@NotNull T data) {
            return new CustomData<>(this, data);
        }

        public static final class Builder<T> {

            private int id;
            @Setter
            @Accessors(fluent = true, chain = true)
            private Function<CustomData<T>, CustomData<T>> copy;
            @Setter
            @Accessors(fluent = true, chain = true)
            private BiConsumer<T, ByteBuf> write;
            @Setter
            @Accessors(fluent = true, chain = true)
            private Function<ByteBuf, T> read;

            public Builder<T> id(int id) {
                this.id = id;
                return this;
            }

            public Builder<T> id(String id) {
                this.id = id.hashCode();
                return this;
            }

            public Type<T> build() {
                return new Type<>(id, copy == null ? Function.identity() : copy, write, read);
            }
        }
    }
}
