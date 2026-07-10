package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

/**
 * Functional interface for receiving sync notifications for short values.
 * The EMPTY instance is a no-op singleton for use as a default/optional listener.
 */
@FunctionalInterface
public interface ShortSyncListener {

    ShortSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, short oldValue, short newValue);
}
