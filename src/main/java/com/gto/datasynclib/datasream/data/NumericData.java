package com.gto.datasynclib.datasream.data;

public sealed interface NumericData extends ImmutableData permits ByteData, ShortData, IntData, LongData, FloatData, DoubleData {

    Number box();

    byte byteValue();

    short shortValue();

    int intValue();

    long longValue();

    float floatValue();

    double doubleValue();
}
