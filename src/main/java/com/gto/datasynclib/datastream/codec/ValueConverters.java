package com.gto.datasynclib.datastream.codec;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * The registry of converters for the values the type set does not cover.
 *
 * <p>Such a value has no written form of its own: it is written as an opaque
 * {@link ValueOps.Type#SELF} value, which means Java serialization — fragile across class changes,
 * unreadable when the class is missing, and opaque in a save file. A converter replaces that with an
 * ordinary carrier value, one of the types the set already has, and turns it back on the way in:</p>
 *
 * <pre>{@code
 * ValueConverters.register(ValueConverters.Converter.<UUID>builder(ValueOps.Type.LONG_ARRAY, UUID.class)
 *         .write((ops, uuid) -> ops.createLongArray(new long[]{
 *                 uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()}))
 *         .read((ops, data) -> {
 *             var longs = ops.getLongArray(data);
 *             return longs.length < 2 ? null : new UUID(longs[0], longs[1]);
 *         })
 *         .build());
 * }</pre>
 *
 * <p>A converter declares the {@link ValueOps.Type} id of the payload it writes, and that id is the
 * whole format: a value with a converter is written and read as its payload, with no wrapper, no flag
 * byte and no converter id on the wire. A UUID is a {@code LONG_ARRAY} in a save file, which is also
 * what {@link ValueOps#createUUID(java.util.UUID)} stores, so both paths reach the same bytes.</p>
 *
 * <p>The payload functions are handed the {@link ValueOps} doing the work and build the payload from
 * its primitives rather than from one carrier's shapes, so a converter converts the same way on every
 * carrier. A class without a converter keeps Java serialization.</p>
 *
 * <h3>Which converter applies</h3>
 * <p>A converter is registered for a class, and matches that class exactly. A converter that should
 * cover a family — an interface, an abstract base, everything under a package — adds a
 * {@link Predicate} over the class:</p>
 *
 * <pre>{@code
 * // any value whose class implements Tagged, with its own payload format
 * ValueConverters.register(ValueConverters.Converter.<Tagged>builder(ValueOps.Type.STRING, Tagged.class)
 *         .when(Tagged.class::isAssignableFrom)
 *         .write((ops, tagged) -> ops.createString(tagged.tag()))
 *         .read((ops, data) -> Tagged.of(ops.getString(data)))
 *         .build());
 * }</pre>
 *
 * <p>Predicates are consulted in registration order and the first match wins, so register the narrow
 * ones first. Without a predicate the lookup is an identity check on the class, which is the fast
 * path.</p>
 *
 * <h3>How a converter is found</h3>
 * <p>By the class of the value being written, and by the class the caller asks for when reading, both
 * through the {@link ClassValue} cache: an entry is registered once and is reachable either way.</p>
 */
public final class ValueConverters {

    /**
     * Turns a value into the carrier value it is stored as — the one whose {@link ValueOps.Type} id the
     * converter declares.
     *
     * <p>{@code ops} is the carrier doing the writing: build the payload with its primitives
     * ({@code createLongArray}, {@code createByteArray}, …) so the converter works for every carrier,
     * and never with a shape only one of them produces.</p>
     */
    @FunctionalInterface
    public interface PayloadWriter<T> {

        /**
         * @return the carrier value {@code value} is stored as, of the converter's type id
         */
        Object write(ValueOps ops, T value);
    }

    /**
     * Turns a stored carrier value back into the value.
     */
    @FunctionalInterface
    public interface PayloadReader<T> {

        /**
         * @param data the stored carrier value; {@link Converter#toValue} answers the absent case
         *             before this is reached
         * @return the value, or {@code null} when the payload holds none
         */
        @Nullable
        T read(ValueOps ops, Object data);
    }

    /**
     * A registered converter: the type id of its payload, the class it handles, the predicate that
     * decides which classes it covers, and the two functions that move a value through its payload.
     *
     * @param id    the {@link ValueOps.Type} id of the payload {@link #write} produces — written in
     *              front of the payload, and the id {@link ValueOps#getTypeId(Object)} reports for the
     *              value
     * @param type  the class this converts, and the key the registry is looked up by
     * @param when  which classes this covers; {@link #EXACT} for the registered class alone
     * @param write value to carrier value
     * @param read  carrier value back to the value
     * @param <T>   the converted type
     */
    public record Converter<T>(byte id, Class<T> type, Predicate<Class<?>> when,
                               PayloadWriter<T> write, PayloadReader<T> read) {

        /**
         * The predicate of a converter registered for its class alone: an identity check, which is
         * what a registration without {@link Builder#when(Predicate)} gets.
         */
        public static final Predicate<Class<?>> EXACT = type -> false;

        /**
         * A builder for the converter of {@code type}, stored as the payload type {@code id} names.
         *
         * <p>The id is part of the format: it says how the payload is written, so changing it makes
         * every stored value of {@code type} unreadable. It is a {@link ValueOps.Type} id rather than
         * one of the converter's own, so two converters whose payloads are the same type share it.</p>
         */
        public static <T> Builder<T> builder(byte id, Class<T> type) {
            return new Builder<>(id, type);
        }

        /**
         * Builds the converter and registers it.
         */
        public Converter<T> build() {
            register(this);
            return this;
        }

        /**
         * The carrier value {@code value} is stored as.
         */
        public Object toPayload(ValueOps ops, T value) {
            return write.write(ops, value);
        }

        /**
         * The value behind the stored {@code data}, or {@code null} when it is absent — one absent
         * value rule for every converter, so no reader has to repeat it.
         */
        @Nullable
        public T toValue(ValueOps ops, Object data) {
            return ops.isNull(data) ? null : read.read(ops, data);
        }

        /**
         * Whether this converter handles {@code candidate}, which it does when the predicate accepts
         * it or when it is the registered class itself.
         */
        public boolean covers(Class<?> candidate) {
            return candidate == type || (when != EXACT && when.test(candidate));
        }
    }

    /**
     * The registrations, in registration order: the order is the priority a predicate-based lookup
     * uses.
     */
    private static final ObjectArrayList<Converter<?>> REGISTRY = new ObjectArrayList<>();

    /**
     * The registrations keyed by the exact class they were declared for, so the common case — a value
     * of exactly that class — is a map hit rather than a scan.
     */
    private static final Reference2ReferenceOpenHashMap<Class<?>, Converter<?>> BY_CLASS = new Reference2ReferenceOpenHashMap<>();

    /**
     * Bumped on every registration, so a lookup answer that predates it is not reused.
     */
    private static volatile int generation;

    /**
     * The per-class lookup. An exact registration answers directly; otherwise the predicates are
     * consulted in registration order.
     *
     * <p>A miss is only cached while the registry has not changed since: the answer for a class may
     * become a different one after a later registration, and {@link #generation} is what tells this
     * cache that its entry is stale.</p>
     */
    private static final ClassValue<Hit> CACHE = new ClassValue<>() {
        @Override
        protected Hit computeValue(Class<?> type) {
            var at = generation;
            var exact = BY_CLASS.get(type);
            if (exact != null) return new Hit(exact, at);
            for (var converter : REGISTRY) {
                if (converter.when() != Converter.EXACT && converter.covers(type)) {
                    return new Hit(converter, at);
                }
            }
            return new Hit(null, at);
        }
    };

    /**
     * One cache entry: the converter found for a class, and the registry generation it was found at.
     */
    private record Hit(@Nullable Converter<?> converter, int generation) {
    }


    /**
     * Registers a converter. The pairs that reach the registry are distinct classes — the id is the
     * payload's type id, which converters with the same payload type legitimately share — so the class
     * is what makes a registration unreachable.
     *
     * @throws IllegalArgumentException if a converter is already registered for exactly that class
     */
    public static synchronized void register(Converter<?> converter) {
        var sameClass = BY_CLASS.get(converter.type);
        if (sameClass != null) {
            throw new IllegalArgumentException("A converter is already registered for " + converter.type().getName()
                    + ", so a second one for that class would never be reached");
        }
        REGISTRY.add(converter);
        BY_CLASS.put(converter.type(), converter);
        generation++;
    }

    /**
     * The converter for {@code type} — its own, or the first registered predicate that covers it —
     * or {@code null} when there is none. The read side's lookup, by the class the caller asks for.
     */
    @Nullable
    public static Converter<?> converter(Class<?> type) {
        return type == null ? null : hit(type).converter();
    }

    /**
     * The converter for the exact class of {@code value}, or {@code null} when there is none — the
     * write side's lookup.
     */
    @Nullable
    public static Converter<?> converterOf(Object value) {
        return value == null ? null : hit(value.getClass()).converter();
    }

    private static Hit hit(Class<?> type) {
        var cached = CACHE.get(type);
        if (cached.generation == generation) return cached;
        CACHE.remove(type);
        return CACHE.get(type);
    }

    /**
     * The number of registered converters.
     */
    public static synchronized int size() {
        return REGISTRY.size();
    }

    /**
     * Builds a converter and registers it.
     */
    public static final class Builder<T> {

        private final byte id;
        private final Class<T> type;
        private Predicate<Class<?>> when = Converter.EXACT;
        private PayloadWriter<T> write;
        private PayloadReader<T> read;

        private Builder(byte id, Class<T> type) {
            this.id = id;
            this.type = type;
        }

        /**
         * Sets the predicate that decides which classes this converter covers, for a converter that
         * stands for a whole family rather than one class. Predicates are consulted in registration
         * order, so a narrow one has to be registered before a broad one.
         *
         * @param when accepts a class; {@link Converter#covers(Class)} also accepts the registered
         *             class itself, so the predicate only has to describe the rest
         */
        public Builder<T> when(Predicate<Class<?>> when) {
            this.when = when;
            return this;
        }

        /**
         * Sets the value-to-payload function. Without one the converter cannot be used for writing.
         */
        public Builder<T> write(PayloadWriter<T> write) {
            this.write = write;
            return this;
        }

        /**
         * Sets the payload-to-value function. Without one the converter cannot be used for reading.
         */
        public Builder<T> read(PayloadReader<T> read) {
            this.read = read;
            return this;
        }

        /**
         * Registers the converter and returns it.
         *
         * @throws IllegalStateException if {@link #write} or {@link #read} was not set
         */
        public Converter<T> build() {
            if (write == null || read == null) {
                throw new IllegalStateException("A converter for " + type.getName() + " needs both write and read");
            }
            return new Converter<>(id, type, when, write, read).build();
        }
    }
}
