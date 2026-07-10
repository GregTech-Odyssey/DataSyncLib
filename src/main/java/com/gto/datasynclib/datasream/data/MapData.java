package com.gto.datasynclib.datasream.data;

public sealed interface MapData extends CollectionData permits DataMapData, StringMapData, IntMapData, LongMapData {
}
