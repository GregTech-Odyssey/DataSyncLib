package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

@FunctionalInterface
public interface ShortSyncListener {

    ShortSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, short oldValue, short newValue);
}
