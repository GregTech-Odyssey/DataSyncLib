package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

@FunctionalInterface
public interface IntSyncListener {

    IntSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, int oldValue, int newValue);
}
