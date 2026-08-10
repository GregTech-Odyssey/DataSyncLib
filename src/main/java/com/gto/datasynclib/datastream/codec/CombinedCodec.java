package com.gto.datasynclib.datastream.codec;

import com.gto.datasynclib.DataSyncCodec;
import com.mojang.datafixers.util.*;
import com.mojang.serialization.Codec;

import java.util.*;
import java.util.function.IntFunction;
import java.util.function.BiFunction;
import java.util.function.Function;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

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

    static <T> DataSyncCodec<T> of(ByteStreamEncoder<? super T> streamWriter, ByteStreamDecoder<? extends T> streamReader, DataEncoder<? super T> dataWriter, DataDecoder<? extends T> dataReader) {
        return DataSyncCodec.of(streamWriter, streamReader, dataWriter, dataReader);
    }

    static <T> DataSyncCodec<T> of(ByteStreamCodec<T> streamCodec, DataCodec<T> dataCodec) {
        return DataSyncCodec.of(streamCodec, streamCodec, dataCodec, dataCodec);
    }

    static <T> DataSyncCodec<T> of(ByteStreamCodec<T> streamCodec) {
        return DataSyncCodec.of(streamCodec, DataCodec.of(streamCodec));
    }

    static <T> DataSyncCodec<T> of(DataCodec<T> dataCodec) {
        return DataSyncCodec.of(ByteStreamCodec.of(dataCodec), dataCodec);
    }

    static <T> DataSyncCodec<T> of(Codec<T> codec) {
        return DataSyncCodec.of(ByteStreamCodec.of(codec), DataCodec.of(codec));
    }

    // ===== Composite builders (unified stream + data) =====

    static <T, F1> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            Function<? super F1, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, constructor));
    }

    static <T, F1, F2> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            BiFunction<? super F1, ? super F2, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, constructor));
    }

    static <T, F1, F2, F3> DataSyncCodec<T> composite(
            CombinedCodec<F1> c1, Function<? super T, ? extends F1> g1,
            CombinedCodec<F2> c2, Function<? super T, ? extends F2> g2,
            CombinedCodec<F3> c3, Function<? super T, ? extends F3> g3,
            Function3<? super F1, ? super F2, ? super F3, ? extends T> constructor) {
        return DataSyncCodec.of(
                ByteStreamCodec.composite(c1.toStreamCodec(), g1, c2.toStreamCodec(), g2, c3.toStreamCodec(), g3, constructor),
                DataCodec.composite(c1.toDataCodec(), g1, c2.toDataCodec(), g2, c3.toDataCodec(), g3, constructor));
    }

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
     * Composite codec for a collection of elements (VarInt count on the network stream, list on disk).
     *
     * @param function factory that creates the collection by expected size (e.g. {@code ArrayList::new})
     * @param codec    element combined codec
     */
    static <T, C extends Collection<T>> DataSyncCodec<C> collection(IntFunction<C> function, CombinedCodec<T> codec) {
        return DataSyncCodec.of(ByteStreamCodec.collection(function, codec.toStreamCodec()), DataCodec.collection(function, codec.toDataCodec()));
    }

    /** Composite codec for {@code List<T>} backed by {@link ArrayList}. */
    static <T> DataSyncCodec<List<T>> list(CombinedCodec<T> codec) {
        return collection(ArrayList::new, codec);
    }

    /** Composite codec for a reference-backed {@code Set<T>} ({@link ReferenceOpenHashSet ReferenceOpenHashSet}). */
    static <T> DataSyncCodec<Set<T>> set(CombinedCodec<T> codec) {
        IntFunction<Set<T>> factory = ReferenceOpenHashSet::new;
        return collection(factory, codec);
    }

    /** Composite codec for an object {@code T[]} array. */
    static <T> DataSyncCodec<T[]> array(Class<T> type, CombinedCodec<T> codec) {
        return DataSyncCodec.of(ByteStreamCodec.array(type, codec.toStreamCodec()), DataCodec.array(type, codec.toDataCodec()));
    }

    /**
     * Composite codec for {@code Map<K, V>} (network and disk each encode key+value entries).
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
}
