package com.gto.datasynclib.datastream.codec;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The registry of <strong>custom value types</strong>: the escape hatch that lets the library bolt its own
 * payload formats onto the carrier as {@link ValueOps.Type#CUSTOM}, without the type set having to know
 * anything about them.
 *
 * <p>A {@link Type} is an id plus the two functions that write and read the payload — the payload
 * itself never has to be a carrier value, which is what makes this the place for objects the ops
 * cannot break into primitives (an NBT tag, a {@code JsonElement}, a registry entry). It is
 * deliberately carrier-agnostic: {@link JavaValueOps} writes the id and then hands the stream to the
 * type's function, and a future carrier does exactly the same.</p>
 *
 * <p>The registry is global and keyed by an {@code int} id, which travels on the wire as a
 * {@code VarInt} — so an id, once shipped, belongs to that type forever, and registering two types
 * under one id is an error rather than a silent override. {@link Type#builder(int)} takes the id,
 * {@link Type#builder(String)} derives it from a name ({@code name.hashCode()}) so a stable string is
 * enough for the registrations this library writes by hand.</p>
 *
 * <h3>This registry is the library's, not a mod's</h3>
 * <p>An id here is a raw {@code int} in one global table that a save file and the network both carry: the
 * ids below 16 are the library's own payloads already — NBT at {@code 0…2}, JSON at {@code 3…5} — nothing
 * reserves a range for anyone else, a collision is fatal rather than a merge, and an id that has shipped can
 * never be handed back. A payload format is also something every reader has to agree on: a client without the
 * type refuses the value outright instead of degrading, so a private format registered here leaks into the
 * wire format of the whole game.</p>
 *
 * <p>A mod's own object is not a payload the carrier should learn — it is a value, and
 * {@link ValueConverters} is where a value goes: register a {@link ValueConverters.Converter} for the class
 * and it travels as one of the types that already exist, so nothing new enters the format and no id is spent.
 * Reach for {@link CustomTypes} only to teach the carrier a payload it does not have, which so far has meant
 * NBT and JSON. Reading a custom value — {@link ValueOps#isCustom(Object)} and
 * {@link ValueOps#getCustom(CustomTypes.Type, Object)} — stays open to everyone, since those are the
 * library's own payloads coming back.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * // how the library itself registers its NBT payload (NbtUtil), not how a mod stores a value:
 * public static final CustomTypes.Type<Tag> TAG_TYPE = CustomTypes.Type.<Tag>builder(0)
 *         .write(NbtUtil::write)
 *         .read(NbtUtil::read)
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
     * @param write writes a payload, without the id prefix
     * @param read  reads a payload, positioned right after the id
     * @param <T>   the payload type, which the carrier never inspects
     */
    public record Type<T>(int id, BiConsumer<T, ByteBuf> write, Function<ByteBuf, T> read) {

        /**
         * A builder for the type registered under {@code id}.
         *
         * @apiNote The registry belongs to the library rather than to a mod — see the class documentation. A
         * mod's own object is a {@link ValueConverters.Converter}, which needs no id at all.
         */
        public static <T> Builder<T> builder(int id) {
            return new Builder<>(id);
        }

        /**
         * A builder whose id is {@code name.hashCode()} — the convenient way to get a stable id from a
         * name, at the cost of the (astronomically unlikely) chance of a collision with another name.
         *
         * @apiNote The registry belongs to the library rather than to a mod — see the class documentation.
         */
        public static <T> Builder<T> builder(String name) {
            return new Builder<>(name.hashCode());
        }
    }

    /**
     * Registers {@code type} under its id.
     *
     * @throws IllegalArgumentException if another type already holds that id
     * @apiNote The registry belongs to the library rather than to a mod — see the class documentation. A
     * mod's own object is stored through a {@link ValueConverters.Converter}, which claims nothing in the
     * format.
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
        private BiConsumer<T, ByteBuf> write;
        private Function<ByteBuf, T> read;

        private Builder(int id) {
            this.id = id;
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
         * Registers the type and returns it — globally, and for good: an id that has shipped cannot be
         * handed back. See the class documentation for who should be doing this.
         */
        public Type<T> build() {
            var type = new Type<>(id, write, read);
            register(type);
            return type;
        }
    }
}
