package com.gto.datasynclib.test;

import com.gto.datasynclib.annotations.SaveToDisk;

/**
 * A plain sub-object of {@link TestEntity}, managed by a dedicated child manager via
 * {@code @AdditionalHolder(childManager = true)}.
 */
class TestEntitySub {

    @SaveToDisk
    int points;

    @SaveToDisk
    boolean prime = false;
}
