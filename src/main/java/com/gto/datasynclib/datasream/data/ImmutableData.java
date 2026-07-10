package com.gto.datasynclib.datasream.data;

public sealed interface ImmutableData extends Data permits NullData, NumericData, CharData, StringData {

    byte[] NULL_BYTES = new byte[]{NULL};

    @Override
    default Data copy() {
        return this;
    }
}
