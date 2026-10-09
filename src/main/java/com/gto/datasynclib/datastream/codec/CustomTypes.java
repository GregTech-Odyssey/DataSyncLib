package com.gto.datasynclib.datastream.codec;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The registry of <strong>custom value types</strong>: the escape hatch that lets an application bolt
 * its own payload format onto the carrier as {@link ValueOps.Type#CUSTOM}, without the type set
 * having to know anything about it.
 *
 * <p>A {@link Type} is an id plus the two functions that write and read the payload — the payload
 * itself never has to be a carrier value, which is what makes this the place for objects the ops
 * cannot break into primitives (an NBT tag, a registry entry, a POJO with its own format). It is
 * deliberately carrier-agnostic: {@link JavaValueOps} writes the id and then hands the stream to the
 * type's function, and a future carrier does exactly the same.</p>
 *
 * <p>The registry is global and keyed by an {@code int} id, which travels on the wire as a
 * {@code VarInt} — so an id, once shipped, belongs to that type forever, and registering two types
 * under one id is an error rather than a silent override. {@link Type#builder(int)} takes the id,
 * {@link Type#builder(String)} derives it from a name ({@code name.hashCode()}) so a stable string
 * is enough for hand-written registrations.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * public static final CustomTypes.Type<MyThing> MY_THING = CustomTypes.Type.<MyThing>builder("my_thing")
 *         .write((thing, buf) -> buf.writeUtf(thing.name()))
 *         .read(buf -> new MyThing(buf.readUtf()))
 *         .copy(MyThing::new)          // optional, used by carriers that copy values
 *         .build();
 * }</pre>
 */
@UtilityClass
public class CustomTypes {

    private final Int2ObjectOpenHashMap<Type<?>> REGISTRY = new Int2ObjectOpenHashMap<>();

    /**
     * A registered custom type: its wire id and the payload codec.
     *
     * @param id    the id written on the wire — unique across the registry, forever
     * @param copy  copies a payload; {@link Function#identity()} when the type did not supply one
     * @param write writes a payload, without the id prefix
     * @param read  reads a payload, positioned right after the id
     * @param <T>   the payload type, which the carrier never inspects
     */
    public record Type<T>(int id, Function<T, T> copy, BiConsumer<T, ByteBuf> write, Function<ByteBuf, T> read) {

        /**
         * A builder for the type registered under {@code id}.
         */
        public static <T> Builder<T> builder(int id) {
            return new Builder<>(id);
        }

        /**
         * A builder whose id is {@code name.hashCode()} — the convenient way to get a stable id from a
         * name, at the cost of the (astronomically unlikely) chance of a collision with another name.
         */
        public static <T> Builder<T> builder(String name) {
            return new Builder<>(name.hashCode());
        }
    }

    /**
     * Registers {@code type} under its id.
     *
     * @throws IllegalArgumentException if another type already holds that id
     */
    public static synchronized void register(Type<?> type) {
        if (REGISTRY.put(type.id(), type) != null) {
            throw new IllegalArgumentException("A custom value type is already registered for id " + type.id());
        }
    }

    /**
     * The type registered under {@code id}, or {@code null} if none is.
     */
    @Nullable
    public static Type<?> type(int id) {
        return REGISTRY.get(id);
    }

    /**
     * Builds a {@link Type} and registers it.
     *
     * @param <T> the payload type
     */
    public static final class Builder<T> {

        private final int id;
        private Function<T, T> copy;
        private BiConsumer<T, ByteBuf> write;
        private Function<ByteBuf, T> read;

        private Builder(int id) {
            this.id = id;
        }

        /**
         * Sets the payload copier, for carriers that have to duplicate a value. Without it the payload
         * is copied by reference (identity).
         */
        public Builder<T> copy(Function<T, T> copy) {
            this.copy = copy;
            return this;
        }

        /**
         * Sets the payload writer — the id is written by the carrier, not here.
         */
        public Builder<T> write(BiConsumer<T, ByteBuf> write) {
            this.write = write;
            return this;
        }

        /**
         * Sets the payload reader, positioned right after the id.
         */
        public Builder<T> read(Function<ByteBuf, T> read) {
            this.read = read;
            return this;
        }

        /**
         * Registers the type and returns it.
         */
        public Type<T> build() {
            var type = new Type<>(id, copy == null ? Function.identity() : copy, write, read);
            register(type);
            return type;
        }
    }
}
