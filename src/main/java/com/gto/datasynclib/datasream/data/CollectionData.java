package com.gto.datasynclib.datasream.data;

public sealed interface CollectionData extends Data permits MapData, ListData, ByteArrayData, IntArrayData, LongArrayData {

    int size();

    boolean isEmpty();
}
