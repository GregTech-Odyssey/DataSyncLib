package com.gto.datasynclib.datastream.data;

public sealed interface MapData extends CollectionData permits DataMapData, StringMapData, IntMapData, LongMapData {
}
