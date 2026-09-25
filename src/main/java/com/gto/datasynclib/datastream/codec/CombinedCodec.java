package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.util.StreamCodecs;
import com.mojang.datafixers.util.*;
import com.mojang.serialization.Codec;
import java.util.*;
import java.util.function.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;

/**
 * A codec usable on <strong>both</strong> serialization paths: the network stream
 * ({@link StreamCodec}, {@code RegistryFriendlyByteBuf}) and the data/persistence path
 * ({@link DataCodec}, {@link com.gto.datasynclib.datastream.data.Data}).
 *
 * <p>Implementations that keep a separate codec per path should override
 * {@link #toDataCodec()}/{@link #toStreamCodec()} to expose the concrete halves, so the
 * composite builders below can pass them down instead of wrapping this codec again.</p>
 *
 * <h3>Layout of the composite builders</h3>
 * <p>{@link #composite} and the container helpers build a codec pair from component codecs.
 * The two paths are <em>not</em> interchangeable: the stream side writes the components in
 * declaration order, while the data side writes a
 * {@link com.gto.datasynclib.datastream.data.ListData} tuple in the same order (see
 * {@link DataCodec#composite}). Decoding reads the tuple positionally with no bounds guard,
 * so a truncated tuple fails with an indexing exception rather than a {@code DataResult}
 * error.</p>
 *
 * <h3>Trade-off: composite builders vs hand-written codecs</h3>
 * <p>The composite builders are concise and keep both paths in sync, but they are assembled from
 * <strong>boxed</strong> components: a primitive component is boxed on encode and unboxed on
 * decode (a {@code double} coordinate travels as a {@code Double}), and the data side always
 * allocates a {@code ListData} tuple with one entry per component. For a type that is just a
 * handful of primitives and is synchronized often — a position, a vector, a bounding box —
 * writing the codec pair by hand is usually worth the extra lines: talk to
 * {@code RegistryFriendlyByteBuf} and the scalar {@code Data} types directly and no boxing happens
 * at all. {@link com.gto.datasynclib.util.StreamCodecs#VEC3I_CODEC} /
 * {@link com.gto.datasynclib.util.DataCodecs#VEC3I_CODEC} (and
 * {@link com.gto.datasynclib.util.DataCodecs#AABB_CODEC}) show that shape, and the pre-registered
 * constants follow it.</p>
 *
 * <p>Rule of thumb: use {@link #composite} to compose types that are already codec-backed or
 * boxed anyway, and hand-write the pair on hot primitive paths.</p>
 *
 * @param <T> the type both halves encode and decode
 */
public interface CombinedCodec<T> extends DataCodec<T>, StreamCodec<RegistryFriendlyByteBuf, T> {

    /**
     * Returns the underlying disk ({@link DataCodec}) that backs this combined codec, or
     * {@code this} if it directly implements the data codec itself. Lets composite utilities
     * pass the concrete codec down and avoid extra bridging when building new combined codecs.
     */
    default DataCodec<T> toDataCodec() {
        return this;
    }

    /**
     * Returns the underlying network ({@link StreamCodec}) that backs this combined codec,
     * or {@code this} if it directly implements the stream codec itself. Lets composite utilities
     * pass the concrete codec down and avoid extra bridging when building new combined codecs.
     */
    default StreamCodec<? super RegistryFriendlyByteBuf, T> toStreamCodec() {
        return this;
    }

    /**
     * Wraps four independent encoder/decoder components — use when the network and persistence
     * formats differ.
     */
    static <T> DataSyncCodec<T> of(
            StreamEncoder<? super RegistryFriendlyByteBuf, ? super T> writer,
            StreamDecoder<? super RegistryFriendlyByteBuf, ? extends T> reader,
            DataEncoder<? super T> dataWriter,
            DataDecoder<? extends T> dataReader) {
        return DataSyncCodec.of(writer, reader, dataWriter, dataReader);
    }

    /**
     * Wraps a distinct stream codec and data codec for the same type.
     */
    static <T> DataSyncCodec<T> of(StreamCodec<? super RegistryFriendlyByteBuf, T> stream, DataCodec<T> data) {
        return DataSyncCodec.of(stream, data);
    }

    /**
     * Adapts a data codec, deriving the stream codec by bridging the disk format onto the network
     * through {@link StreamCodecs#fromData} (the payload keeps the {@link DataCodec} layout and
     * needs the receiving side to share the same registry context).
     */
    static <T> DataSyncCodec<T> of(DataCodec<T> data) {
        return DataSyncCodec.of(data);
    }

    /**
     * Adapts a Mojang DFU {@link Codec} to both paths via {@code DataOps}.
     */
    static <T> DataSyncCodec<T> of(Codec<T> codec) {
        return DataSyncCodec.of(ByteBufCodecs.fromCodecWithRegistries(codec), DataCodec.of(codec));
    }

    // ===== Composite builders (unified stream + data) =====

    /**
     * Composite codec for {@code T} from 1 component codec. The stream side writes the component in
     * declaration order, while the data side encodes it as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     * Primitive components are boxed: see the interface documentation for when to hand-write instead.
     */
    static <T, F1> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<T, F1> g1,
            Function<F1, T> constructor) {
        return DataSyncCodec.of(
                StreamCodec.composite(c1.toStreamCodec(), g1, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, constructor));
    }

    /**
     * Composite codec for {@code T} from 2 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<T, F1> g1,
            CombinedCodec<F2> c2, Function<T, F2> g2,
            BiFunction<F1, F2, T> constructor) {
        return DataSyncCodec.of(
                StreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, constructor));
    }

    /**
     * Composite codec for {@code T} from 3 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<T, F1> g1,
            CombinedCodec<F2> c2, Function<T, F2> g2,
            CombinedCodec<F3> c3, Function<T, F3> g3,
            Function3<F1, F2, F3, T> constructor) {
        return DataSyncCodec.of(
                StreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, constructor));
    }

    /**
     * Composite codec for {@code T} from 4 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<T, F1> g1,
            CombinedCodec<F2> c2, Function<T, F2> g2,
            CombinedCodec<F3> c3, Function<T, F3> g3,
            CombinedCodec<F4> c4, Function<T, F4> g4,
            Function4<F1, F2, F3, F4, T> constructor) {
        return DataSyncCodec.of(
                StreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, constructor));
    }

    /**
     * Composite codec for {@code T} from 5 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<T, F1> g1,
            CombinedCodec<F2> c2, Function<T, F2> g2,
            CombinedCodec<F3> c3, Function<T, F3> g3,
            CombinedCodec<F4> c4, Function<T, F4> g4,
            CombinedCodec<F5> c5, Function<T, F5> g5,
            Function5<F1, F2, F3, F4, F5, T> constructor) {
        return DataSyncCodec.of(
                StreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, constructor));
    }

    /**
     * Composite codec for {@code T} from 6 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<T, F1> g1,
            CombinedCodec<F2> c2, Function<T, F2> g2,
            CombinedCodec<F3> c3, Function<T, F3> g3,
            CombinedCodec<F4> c4, Function<T, F4> g4,
            CombinedCodec<F5> c5, Function<T, F5> g5,
            CombinedCodec<F6> c6, Function<T, F6> g6,
            Function6<F1, F2, F3, F4, F5, F6, T> constructor) {
        return DataSyncCodec.of(
                StreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, constructor));
    }

    /**
     * Composite codec for a collection of elements: the network stream writes a VarInt size
     * followed by the elements, the disk side writes a {@code ListData} with no length prefix.
     * An empty (or non-list) payload decodes to {@code factory.apply(1)} — an empty container —
     * rather than {@code null}. Elements are boxed on both paths; see the interface
     * documentation for the trade-off.
     *
     * @param factory factory that creates the collection by expected size (e.g. {@code ArrayList::new})
     * @param codec   element combined codec
     */
    static <T, C extends Collection<T>> DataSyncCodec<C> collection(IntFunction<C> factory, CombinedCodec<T> codec) {
        return DataSyncCodec.of(
                ByteBufCodecs.collection(factory, codec.toStreamCodec()),
                DataCodec.collection(factory, codec.toDataCodec()));
    }

    /**
     * Composite codec for {@code List<T>} backed by {@link ArrayList}.
     */
    static <T> DataSyncCodec<List<T>> list(CombinedCodec<T> codec) {
        return collection(ArrayList::new, codec);
    }

    /**
     * Composite codec for a reference-backed {@code Set<T>}
     * ({@link it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet ReferenceOpenHashSet}).
     */
    static <T> DataSyncCodec<Set<T>> set(CombinedCodec<T> codec) {
        return collection(it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet::new, codec);
    }

    /**
     * Composite codec for an object {@code T[]} array: the network stream writes the length
     * (VarInt) followed by the elements, the disk side writes a {@code ListData}. An empty (or
     * non-list) payload decodes to a zero-length array. Elements are boxed; primitive arrays have
     * their own codecs and do not need this builder.
     *
     * <p>Every element must be non-null — the compact per-element format has no presence marker.
     * Array <em>fields</em> are unaffected: they are encoded by the access layer, which writes a
     * presence boolean per element and therefore does support null slots.</p>
     */
    static <T> DataSyncCodec<T[]> array(Class<T> type, CombinedCodec<T> codec) {
        return DataSyncCodec.of(
                StreamCodecs.array(type, codec.toStreamCodec()),
                DataCodec.array(type, codec.toDataCodec()));
    }

    /**
     * Composite codec for {@code Map<K, V>}: the network stream writes a VarInt size followed by
     * key/value pairs, the disk side writes a flat {@code ListData} in the same interleaved order.
     * A payload with fewer than two entries decodes to {@code factory.apply(1)} — an empty map.
     * Keys and values are boxed.
     *
     * @param factory factory that creates the map by expected size (e.g. {@code HashMap::new})
     * @param key     key combined codec
     * @param value   value combined codec
     */
    static <K, V, M extends Map<K, V>> DataSyncCodec<M> map(IntFunction<M> factory, CombinedCodec<K> key, CombinedCodec<V> value) {
        return DataSyncCodec.of(
                ByteBufCodecs.map(factory, key.toStreamCodec(), value.toStreamCodec()),
                DataCodec.map(factory, key.toDataCodec(), value.toDataCodec()));
    }
}
