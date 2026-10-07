package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.DataSyncCodec;
import com.mojang.datafixers.util.*;
import com.mojang.serialization.Codec;

import java.util.*;
import java.util.function.IntFunction;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

/**
 * A codec usable on <strong>both</strong> serialization paths: the network stream
 * ({@link ByteStreamCodec}, {@code FriendlyByteBuf}) and the data/persistence path
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
 * {@code FriendlyByteBuf} and the scalar {@code Data} types directly and no boxing happens at
 * all. {@link com.gto.datasynclib.util.StreamCodecs#VEC3I_CODEC} /
 * {@link com.gto.datasynclib.util.DataCodecs#VEC3I_CODEC} (and
 * {@link com.gto.datasynclib.util.DataCodecs#AABB_CODEC}) show that shape, and the pre-registered
 * constants follow it.</p>
 *
 * <p>Rule of thumb: use {@link #composite} to compose types that are already codec-backed or
 * boxed anyway, and hand-write the pair on hot primitive paths.</p>
 *
 * <p>The {@link #optional} adapters mark a {@code null} value — or, with the default overloads, one
 * equal to a default — as absent on both paths at once:
 * {@link com.gto.datasynclib.datastream.data.NullData#INSTANCE} on disk, a {@code false} boolean on
 * the wire. Every builder that takes a codec also has an instance form on the codec itself
 * ({@code CODEC.optional()}, {@code CODEC.asKey(HashMap::new, KEY_CODEC)}, {@code CODEC.list()},
 * {@code CODEC.toDataSyncCodec()}, …), which on this interface keeps the result combined:
 * {@link #asKey} / {@link #asValue} / {@link #collection} / {@link #array} / {@link #convert} all
 * return a {@link DataSyncCodec} covering both paths.</p>
 *
 * @param <T> the type both halves encode and decode
 */
public interface CombinedCodec<T> extends DataCodec<T>, ByteStreamCodec<T> {

    /**
     * Returns the underlying disk ({@link DataCodec}) that backs this combined codec, or
     * {@code this} if it directly implements the data codec itself. Lets composite utilities
     * pass the concrete codec down and avoid extra bridging when building new combined codecs.
     */
    default DataCodec<T> toDataCodec() {
        return this;
    }

    /**
     * Returns the underlying network ({@link ByteStreamCodec}) that backs this combined codec,
     * or {@code this} if it directly implements the stream codec itself. Lets composite utilities
     * pass the concrete codec down and avoid extra bridging when building new combined codecs.
     */
    default ByteStreamCodec<T> toStreamCodec() {
        return this;
    }

    /**
     * Wraps four independent encoder/decoder components — use when the network and persistence
     * formats differ.
     */
    static <T> DataSyncCodec<T> of(ByteStreamEncoder<? super T> streamWriter, ByteStreamDecoder<? extends T> streamReader, DataEncoder<? super T> dataWriter, DataDecoder<? extends T> dataReader) {
        return DataSyncCodec.of(streamWriter, streamReader, dataWriter, dataReader);
    }

    /**
     * Wraps a distinct stream codec and data codec for the same type.
     */
    static <T> DataSyncCodec<T> of(ByteStreamCodec<T> streamCodec, DataCodec<T> dataCodec) {
        return DataSyncCodec.of(streamCodec, streamCodec, dataCodec, dataCodec);
    }

    /**
     * Adapts a stream codec, deriving the data codec by carrying
     * {@link com.gto.datasynclib.datastream.data.Data#writeToBytes() bytes} inside a
     * {@code ByteArrayData} (uses the same layout on both paths, at the cost of an extra copy).
     */
    static <T> DataSyncCodec<T> of(ByteStreamCodec<T> streamCodec) {
        return DataSyncCodec.of(streamCodec, DataCodec.of(streamCodec));
    }

    /**
     * Adapts a data codec, deriving the stream codec by writing its bytes as a length-prefixed
     * byte array on the network.
     */
    static <T> DataSyncCodec<T> of(DataCodec<T> dataCodec) {
        return DataSyncCodec.of(ByteStreamCodec.of(dataCodec), dataCodec);
    }

    /**
     * Adapts a Mojang DFU {@link Codec} to both paths via {@code DataOps}.
     */
    static <T> DataSyncCodec<T> of(Codec<T> codec) {
        return DataSyncCodec.of(ByteStreamCodec.of(codec), DataCodec.of(codec));
    }

    // ===== Composite builders (unified stream + data) =====

    /**
     * Composite codec for {@code T} from 1 component codec. The stream side writes the component in
     * declaration order, while the data side encodes it as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     * Primitive components are boxed: see the interface documentation for when to hand-write instead.
     */
    static <T, F1> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            Function<? super F1, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, constructor));
    }

    /**
     * Composite codec for {@code T} from 2 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            BiFunction<? super F1, ? super F2, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, constructor));
    }

    /**
     * Composite codec for {@code T} from 3 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            Function3<? super F1, ? super F2, ? super F3, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, constructor));
    }

    /**
     * Composite codec for {@code T} from 4 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            Function4<? super F1, ? super F2, ? super F3, ? super F4, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, constructor));
    }

    /**
     * Composite codec for {@code T} from 5 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            Function5<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, constructor));
    }

    /**
     * Composite codec for {@code T} from 6 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            Function6<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, constructor));
    }

    /**
     * Composite codec for {@code T} from 7 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            CombinedCodec<F7> c7, Function<? super T, ? extends F7> g7,
            Function7<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, c7.toStreamCodec(), g7, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, c7.toDataCodec(), g7, constructor));
    }

    /**
     * Composite codec for {@code T} from 8 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            CombinedCodec<F7> c7, Function<? super T, ? extends F7> g7,
            CombinedCodec<F8> c8, Function<? super T, ? extends F8> g8,
            Function8<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, c7.toStreamCodec(), g7, c8.toStreamCodec(), g8, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, c7.toDataCodec(), g7, c8.toDataCodec(), g8, constructor));
    }

    /**
     * Composite codec for {@code T} from 9 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            CombinedCodec<F7> c7, Function<? super T, ? extends F7> g7,
            CombinedCodec<F8> c8, Function<? super T, ? extends F8> g8,
            CombinedCodec<F9> c9, Function<? super T, ? extends F9> g9,
            Function9<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, c7.toStreamCodec(), g7, c8.toStreamCodec(), g8, c9.toStreamCodec(), g9, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, c7.toDataCodec(), g7, c8.toDataCodec(), g8, c9.toDataCodec(), g9, constructor));
    }

    /**
     * Composite codec for {@code T} from 10 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            CombinedCodec<F7> c7, Function<? super T, ? extends F7> g7,
            CombinedCodec<F8> c8, Function<? super T, ? extends F8> g8,
            CombinedCodec<F9> c9, Function<? super T, ? extends F9> g9,
            CombinedCodec<F10> c10, Function<? super T, ? extends F10> g10,
            Function10<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, c7.toStreamCodec(), g7, c8.toStreamCodec(), g8, c9.toStreamCodec(), g9, c10.toStreamCodec(), g10, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, c7.toDataCodec(), g7, c8.toDataCodec(), g8, c9.toDataCodec(), g9, c10.toDataCodec(), g10, constructor));
    }

    /**
     * Composite codec for {@code T} from 11 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            CombinedCodec<F7> c7, Function<? super T, ? extends F7> g7,
            CombinedCodec<F8> c8, Function<? super T, ? extends F8> g8,
            CombinedCodec<F9> c9, Function<? super T, ? extends F9> g9,
            CombinedCodec<F10> c10, Function<? super T, ? extends F10> g10,
            CombinedCodec<F11> c11, Function<? super T, ? extends F11> g11,
            Function11<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, c7.toStreamCodec(), g7, c8.toStreamCodec(), g8, c9.toStreamCodec(), g9, c10.toStreamCodec(), g10, c11.toStreamCodec(), g11, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, c7.toDataCodec(), g7, c8.toDataCodec(), g8, c9.toDataCodec(), g9, c10.toDataCodec(), g10, c11.toDataCodec(), g11, constructor));
    }

    /**
     * Composite codec for {@code T} from 12 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            CombinedCodec<F7> c7, Function<? super T, ? extends F7> g7,
            CombinedCodec<F8> c8, Function<? super T, ? extends F8> g8,
            CombinedCodec<F9> c9, Function<? super T, ? extends F9> g9,
            CombinedCodec<F10> c10, Function<? super T, ? extends F10> g10,
            CombinedCodec<F11> c11, Function<? super T, ? extends F11> g11,
            CombinedCodec<F12> c12, Function<? super T, ? extends F12> g12,
            Function12<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, c7.toStreamCodec(), g7, c8.toStreamCodec(), g8, c9.toStreamCodec(), g9, c10.toStreamCodec(), g10, c11.toStreamCodec(), g11, c12.toStreamCodec(), g12, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, c7.toDataCodec(), g7, c8.toDataCodec(), g8, c9.toDataCodec(), g9, c10.toDataCodec(), g10, c11.toDataCodec(), g11, c12.toDataCodec(), g12, constructor));
    }

    /**
     * Composite codec for {@code T} from 13 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            CombinedCodec<F7> c7, Function<? super T, ? extends F7> g7,
            CombinedCodec<F8> c8, Function<? super T, ? extends F8> g8,
            CombinedCodec<F9> c9, Function<? super T, ? extends F9> g9,
            CombinedCodec<F10> c10, Function<? super T, ? extends F10> g10,
            CombinedCodec<F11> c11, Function<? super T, ? extends F11> g11,
            CombinedCodec<F12> c12, Function<? super T, ? extends F12> g12,
            CombinedCodec<F13> c13, Function<? super T, ? extends F13> g13,
            Function13<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, c7.toStreamCodec(), g7, c8.toStreamCodec(), g8, c9.toStreamCodec(), g9, c10.toStreamCodec(), g10, c11.toStreamCodec(), g11, c12.toStreamCodec(), g12, c13.toStreamCodec(), g13, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, c7.toDataCodec(), g7, c8.toDataCodec(), g8, c9.toDataCodec(), g9, c10.toDataCodec(), g10, c11.toDataCodec(), g11, c12.toDataCodec(), g12, c13.toDataCodec(), g13, constructor));
    }

    /**
     * Composite codec for {@code T} from 14 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            CombinedCodec<F7> c7, Function<? super T, ? extends F7> g7,
            CombinedCodec<F8> c8, Function<? super T, ? extends F8> g8,
            CombinedCodec<F9> c9, Function<? super T, ? extends F9> g9,
            CombinedCodec<F10> c10, Function<? super T, ? extends F10> g10,
            CombinedCodec<F11> c11, Function<? super T, ? extends F11> g11,
            CombinedCodec<F12> c12, Function<? super T, ? extends F12> g12,
            CombinedCodec<F13> c13, Function<? super T, ? extends F13> g13,
            CombinedCodec<F14> c14, Function<? super T, ? extends F14> g14,
            Function14<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, c7.toStreamCodec(), g7, c8.toStreamCodec(), g8, c9.toStreamCodec(), g9, c10.toStreamCodec(), g10, c11.toStreamCodec(), g11, c12.toStreamCodec(), g12, c13.toStreamCodec(), g13, c14.toStreamCodec(), g14, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, c7.toDataCodec(), g7, c8.toDataCodec(), g8, c9.toDataCodec(), g9, c10.toDataCodec(), g10, c11.toDataCodec(), g11, c12.toDataCodec(), g12, c13.toDataCodec(), g13, c14.toDataCodec(), g14, constructor));
    }

    /**
     * Composite codec for {@code T} from 15 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            CombinedCodec<F7> c7, Function<? super T, ? extends F7> g7,
            CombinedCodec<F8> c8, Function<? super T, ? extends F8> g8,
            CombinedCodec<F9> c9, Function<? super T, ? extends F9> g9,
            CombinedCodec<F10> c10, Function<? super T, ? extends F10> g10,
            CombinedCodec<F11> c11, Function<? super T, ? extends F11> g11,
            CombinedCodec<F12> c12, Function<? super T, ? extends F12> g12,
            CombinedCodec<F13> c13, Function<? super T, ? extends F13> g13,
            CombinedCodec<F14> c14, Function<? super T, ? extends F14> g14,
            CombinedCodec<F15> c15, Function<? super T, ? extends F15> g15,
            Function15<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, c7.toStreamCodec(), g7, c8.toStreamCodec(), g8, c9.toStreamCodec(), g9, c10.toStreamCodec(), g10, c11.toStreamCodec(), g11, c12.toStreamCodec(), g12, c13.toStreamCodec(), g13, c14.toStreamCodec(), g14, c15.toStreamCodec(), g15, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, c7.toDataCodec(), g7, c8.toDataCodec(), g8, c9.toDataCodec(), g9, c10.toDataCodec(), g10, c11.toDataCodec(), g11, c12.toDataCodec(), g12, c13.toDataCodec(), g13, c14.toDataCodec(), g14, c15.toDataCodec(), g15, constructor));
    }

    /**
     * Composite codec for {@code T} from 16 component codecs. The stream side writes the components in
     * declaration order, while the data side encodes them as a {@code ListData} tuple in the same order,
     * so the two wire formats are not identical — see {@link DataCodec#composite} for the data layout.
     */
    static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15, F16> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            CombinedCodec<F4> c4, Function<? super T, ? extends F4> g4,
            CombinedCodec<F5> c5, Function<? super T, ? extends F5> g5,
            CombinedCodec<F6> c6, Function<? super T, ? extends F6> g6,
            CombinedCodec<F7> c7, Function<? super T, ? extends F7> g7,
            CombinedCodec<F8> c8, Function<? super T, ? extends F8> g8,
            CombinedCodec<F9> c9, Function<? super T, ? extends F9> g9,
            CombinedCodec<F10> c10, Function<? super T, ? extends F10> g10,
            CombinedCodec<F11> c11, Function<? super T, ? extends F11> g11,
            CombinedCodec<F12> c12, Function<? super T, ? extends F12> g12,
            CombinedCodec<F13> c13, Function<? super T, ? extends F13> g13,
            CombinedCodec<F14> c14, Function<? super T, ? extends F14> g14,
            CombinedCodec<F15> c15, Function<? super T, ? extends F15> g15,
            CombinedCodec<F16> c16, Function<? super T, ? extends F16> g16,
            Function16<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? super F16, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, c4.toStreamCodec(), g4, c5.toStreamCodec(), g5, c6.toStreamCodec(), g6, c7.toStreamCodec(), g7, c8.toStreamCodec(), g8, c9.toStreamCodec(), g9, c10.toStreamCodec(), g10, c11.toStreamCodec(), g11, c12.toStreamCodec(), g12, c13.toStreamCodec(), g13, c14.toStreamCodec(), g14, c15.toStreamCodec(), g15, c16.toStreamCodec(), g16, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, c4.toDataCodec(), g4, c5.toDataCodec(), g5, c6.toDataCodec(), g6, c7.toDataCodec(), g7, c8.toDataCodec(), g8, c9.toDataCodec(), g9, c10.toDataCodec(), g10, c11.toDataCodec(), g11, c12.toDataCodec(), g12, c13.toDataCodec(), g13, c14.toDataCodec(), g14, c15.toDataCodec(), g15, c16.toDataCodec(), g16, constructor));
    }

    /**
     * Composite codec for a collection of elements: the network stream writes a VarInt size
     * followed by the elements, the disk side writes a {@code ListData} with no length prefix.
     * An empty (or non-list) payload decodes to {@code function.apply(1)} — an empty container —
     * rather than {@code null}. Elements are boxed on both paths; see the interface
     * documentation for the trade-off.
     *
     * @param function factory that creates the collection by expected size (e.g. {@code ArrayList::new})
     * @param codec    element combined codec
     */
    static <T, C extends Collection<T>> DataSyncCodec<C> collection(IntFunction<C> function, CombinedCodec<T> codec) {
        return DataSyncCodec.of(ByteStreamCodec.collection(function, codec.toStreamCodec()), DataCodec.collection(function, codec.toDataCodec()));
    }

    /**
     * Composite codec for {@code List<T>} backed by {@link ArrayList}.
     */
    static <T> DataSyncCodec<List<T>> list(CombinedCodec<T> codec) {
        return collection(ArrayList::new, codec);
    }

    /**
     * Composite codec for a reference-backed {@code Set<T>} ({@link ReferenceOpenHashSet ReferenceOpenHashSet}).
     */
    static <T> DataSyncCodec<Set<T>> set(CombinedCodec<T> codec) {
        IntFunction<Set<T>> factory = ReferenceOpenHashSet::new;
        return collection(factory, codec);
    }

    /**
     * Composite codec for an object {@code T[]} array: the network stream writes the length
     * (VarInt) followed by the elements, the disk side writes a {@code ListData}. An empty (or
     * non-list) payload decodes to a zero-length array. Elements are boxed; primitive arrays have
     * their own codecs and do not need this builder.
     */
    static <T> DataSyncCodec<T[]> array(Class<T> type, CombinedCodec<T> codec) {
        return DataSyncCodec.of(ByteStreamCodec.array(type, codec.toStreamCodec()), DataCodec.array(type, codec.toDataCodec()));
    }

    /**
     * Composite codec for {@code Map<K, V>}: the network stream writes a VarInt size followed by
     * key/value pairs, the disk side writes a flat {@code ListData} in the same interleaved order.
     * A payload with fewer than two entries decodes to {@code function.apply(1)} — an empty map.
     * Keys and values are boxed.
     *
     * @param function   factory that creates the map by expected size (e.g. {@code HashMap::new})
     * @param keyCodec   key combined codec
     * @param valueCodec value combined codec
     */
    static <K, V, M extends Map<K, V>> DataSyncCodec<M> map(IntFunction<M> function, CombinedCodec<K> keyCodec, CombinedCodec<V> valueCodec) {
        return DataSyncCodec.of(
                ByteStreamCodec.map(function, keyCodec.toStreamCodec(), valueCodec.toStreamCodec()),
                DataCodec.map(function, keyCodec.toDataCodec(), valueCodec.toDataCodec()));
    }

    // ===== Optional adapters (unified stream + data) =====

    /**
     * This codec made nullable on <strong>both</strong> paths at once: a {@code null} value is
     * stored as absent and decodes back to {@code null}, while any other value is stored by this
     * codec.
     *
     * <p>The two paths mark "absent" differently, because only the data path has a type tag to
     * spare: the disk form writes {@link com.gto.datasynclib.datastream.data.NullData#INSTANCE}
     * (one byte, the null tag) and the network form writes a {@code false} boolean — so a present
     * value costs one extra boolean byte on the wire and nothing on disk.</p>
     *
     * <p>Declared here (rather than only inherited) because it overrides both
     * {@link DataCodec#optional()} and {@link ByteStreamCodec#optional()} with the common
     * {@link DataSyncCodec} return type. Equivalent to {@code CombinedCodec.optional(this)}.</p>
     */
    @Override
    default DataSyncCodec<T> optional() {
        return CombinedCodec.optional(this);
    }

    /**
     * This codec made nullable with a default: a {@code null} value, or one that
     * {@link Objects#equals(Object, Object) equals} {@code defaultValue}, is stored as absent on
     * both paths — so a value at its default survives a round-trip but is never transmitted, the
     * usual win when the default is also the common value — and decodes back to the default.
     *
     * <p>Equivalent to {@code CombinedCodec.optional(this, defaultValue)}.</p>
     *
     * @param defaultValue the value that is stored as absent; a {@code null} default degrades to
     *                     plain {@link #optional()}
     */
    @Override
    default DataSyncCodec<T> optional(T defaultValue) {
        return CombinedCodec.optional(this, defaultValue);
    }

    /**
     * This codec made nullable with a default produced by {@code defaultSupplier} — the supplier
     * form of {@link #optional(Object)}, for a default that is not a constant (a fresh collection,
     * a value read from config, …).
     *
     * <p>As on the two half-interfaces, a bare lambda or method reference is ambiguous between
     * {@link #optional(Object)} and this overload ({@code codec.optional(ArrayList::new)} does not
     * compile) because {@code T} is already fixed — pass a typed {@code Supplier<T>} variable or
     * cast the lambda.</p>
     *
     * <p>The supplier is shared by both halves and consulted once per encode (for the equality
     * check) and once per decode of an absent value on the path being used, so it should be
     * side-effect free; a fresh mutable default is re-created on every decode.</p>
     *
     * <p>Equivalent to {@code CombinedCodec.optional(this, defaultSupplier)}.</p>
     *
     * @param defaultSupplier supplies the value that is stored as absent
     */
    @Override
    default DataSyncCodec<T> optional(Supplier<? extends T> defaultSupplier) {
        return CombinedCodec.optional(this, defaultSupplier);
    }

    /**
     * {@link #optional()}, as a builder over an arbitrary codec. Both halves are built from
     * {@link #toStreamCodec()}/{@link #toDataCodec()}, so a codec that keeps separate
     * implementations per path reuses them here instead of being bridged again.
     *
     * @param codec codec for the present (non-{@code null}) value
     * @param <T>   the value type
     */
    static <T> DataSyncCodec<T> optional(CombinedCodec<T> codec) {
        return DataSyncCodec.of(
                ByteStreamCodec.optional(codec.toStreamCodec()),
                DataCodec.optional(codec.toDataCodec()));
    }

    /**
     * {@link #optional(Object)}, as a builder over an arbitrary codec.
     *
     * @param codec        codec for the present value
     * @param defaultValue the value that is stored as absent; a {@code null} default degrades to
     *                     plain {@link #optional(CombinedCodec)}
     * @param <T>          the value type
     */
    static <T> DataSyncCodec<T> optional(CombinedCodec<T> codec, T defaultValue) {
        return DataSyncCodec.of(
                ByteStreamCodec.optional(codec.toStreamCodec(), defaultValue),
                DataCodec.optional(codec.toDataCodec(), defaultValue));
    }

    /**
     * {@link #optional(Supplier)}, as a builder over an arbitrary codec.
     *
     * <p>As on the instance form, a bare lambda or method reference is ambiguous between this
     * overload and {@link #optional(CombinedCodec, Object)} — {@code optional(codec, ArrayList::new)}
     * does not compile — so pass a typed {@code Supplier<T>} variable or cast the lambda.</p>
     *
     * @param codec           codec for the present value
     * @param defaultSupplier supplies the value that is stored as absent
     * @param <T>             the value type
     */
    static <T> DataSyncCodec<T> optional(CombinedCodec<T> codec, Supplier<? extends T> defaultSupplier) {
        return DataSyncCodec.of(
                ByteStreamCodec.optional(codec.toStreamCodec(), defaultSupplier),
                DataCodec.optional(codec.toDataCodec(), defaultSupplier));
    }

    // ===== Instance builder mirrors =====
    // Same rule as on the two halves (see DataCodec), plus what a combined codec needs on top: the
    // builders both halves declare are overridden here so the receiver keeps covering both paths
    // and the result stays a DataSyncCodec, and the map builder gets an extra
    // CombinedCodec-parameter overload — that argument type is the only one more specific than what
    // DataCodec and ByteStreamCodec already offer.

    /**
     * Wraps this codec as a combined codec — the instance form of
     * {@code CombinedCodec.of(DataCodec)} / {@code of(ByteStreamCodec)}. Both halves come from
     * {@link #toStreamCodec()}/{@link #toDataCodec()}, so an implementation that already is a
     * {@link DataSyncCodec} short-circuits this to itself, and one that only holds the two codecs
     * gets them reused instead of bridged.
     */
    @Override
    default DataSyncCodec<T> toDataSyncCodec() {
        return DataSyncCodec.of(toStreamCodec(), toDataCodec());
    }

    /**
     * Adapts this codec through a pair of converter functions on both paths — the combined form of
     * the instance {@code convert} of the two halves, applied to {@link #toStreamCodec()} and
     * {@link #toDataCodec()} so each path keeps its own codec instead of being bridged.
     */
    @Override
    default <V> DataSyncCodec<V> convert(Function<? super V, ? extends T> encodeConverter, Function<? super T, ? extends V> decodeConverter) {
        return DataSyncCodec.of(
                ByteStreamCodec.convert(toStreamCodec(), encodeConverter, decodeConverter),
                DataCodec.convert(toDataCodec(), encodeConverter, decodeConverter));
    }

    /**
     * Map codec keyed by this codec on both paths, with {@code valueCodec} for the values — the
     * instance form of {@link #map(IntFunction, CombinedCodec, CombinedCodec)}.
     */
    default <V, M extends Map<T, V>> DataSyncCodec<M> asKey(IntFunction<M> function, CombinedCodec<V> valueCodec) {
        return CombinedCodec.map(function, this, valueCodec);
    }

    /**
     * Map codec valued by this codec on both paths, with {@code keyCodec} for the keys — the
     * instance form of {@link #map(IntFunction, CombinedCodec, CombinedCodec)}, with this codec on
     * the value side.
     */
    default <K, M extends Map<K, T>> DataSyncCodec<M> asValue(IntFunction<M> function, CombinedCodec<K> keyCodec) {
        return CombinedCodec.map(function, keyCodec, this);
    }

    /**
     * Collection codec over this codec's elements on both paths — the instance form of
     * {@link #collection(IntFunction, CombinedCodec)}.
     */
    @Override
    default <C extends Collection<T>> DataSyncCodec<C> collection(IntFunction<C> function) {
        return CombinedCodec.collection(function, this);
    }

    /**
     * {@link List} codec (backed by {@link ArrayList}) over this codec's elements — the instance
     * form of {@link #list(CombinedCodec)}.
     */
    default DataSyncCodec<List<T>> list() {
        return CombinedCodec.list(this);
    }

    /**
     * Reference-backed {@link Set} codec over this codec's elements — the instance form of
     * {@link #set(CombinedCodec)}.
     */
    default DataSyncCodec<Set<T>> set() {
        return CombinedCodec.set(this);
    }

    /**
     * Object-array codec over this codec's elements on both paths — the instance form of
     * {@link #array(Class, CombinedCodec)}.
     */
    @Override
    default DataSyncCodec<T[]> array(Class<T> type) {
        return CombinedCodec.array(type, this);
    }
}
