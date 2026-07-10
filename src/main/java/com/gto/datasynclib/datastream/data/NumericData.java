package com.gto.datasynclib.datastream.data;

/**
 * Marker interface for numeric Data subtypes.
 * Provides numeric conversion methods (byteValue, shortValue, intValue, etc.).
 */
public sealed interface NumericData extends ImmutableData permits ByteData, ShortData, IntData, LongData, FloatData, DoubleData {

    Number box();

    byte byteValue();

    short shortValue();

    int intValue();

    long longValue();

    float floatValue();

    double doubleValue();
}
