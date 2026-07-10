package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

/**
 * Functional interface for receiving sync notifications for long values.
 * The EMPTY instance is a no-op singleton for use as a default/optional listener.
 */
@FunctionalInterface
public interface LongSyncListener {

    LongSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, long oldValue, long newValue);
}
