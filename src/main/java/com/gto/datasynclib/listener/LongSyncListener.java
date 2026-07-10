package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

@FunctionalInterface
public interface LongSyncListener {

    LongSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, long oldValue, long newValue);
}
