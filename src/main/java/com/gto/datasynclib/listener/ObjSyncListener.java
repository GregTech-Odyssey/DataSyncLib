package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

@FunctionalInterface
public interface ObjSyncListener<T> {

    ObjSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, T oldValue, T newValue);
}
