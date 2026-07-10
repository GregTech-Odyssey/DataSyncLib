package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

@FunctionalInterface
public interface DoubleSyncListener {

    DoubleSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, double oldValue, double newValue);
}
