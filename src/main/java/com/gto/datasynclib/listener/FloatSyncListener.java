package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

@FunctionalInterface
public interface FloatSyncListener {

    FloatSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, float oldValue, float newValue);
}
