package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

@FunctionalInterface
public interface ByteSyncListener {

    ByteSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, byte oldValue, byte newValue);
}
