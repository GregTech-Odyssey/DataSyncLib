package com.gto.datasynclib.datastream.data;

/**
 * Marker interface for immutable Data subtypes.
 * Provides a default copy() that returns this (identity).
 */
public sealed interface ImmutableData extends Data permits NullData, NumericData, CharData, StringData {

    byte[] NULL_BYTES = new byte[]{NULL};

    @Override
    default Data copy() {
        return this;
    }
}
