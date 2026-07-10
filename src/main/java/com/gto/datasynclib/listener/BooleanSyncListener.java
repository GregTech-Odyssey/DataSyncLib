package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

@FunctionalInterface
public interface BooleanSyncListener {

    BooleanSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, boolean oldValue, boolean newValue);
}
