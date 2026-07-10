package com.gto.datasynclib.datastream.data;

/**
 * Marker interface for collection-type Data subtypes (lists, maps, arrays)
 * that have size and emptiness semantics.
 */
public sealed interface CollectionData extends Data permits MapData, ListData, ByteArrayData, IntArrayData, LongArrayData {

    int size();

    boolean isEmpty();
}
