package com.gto.datasynclib.listener;

import com.gto.datasynclib.LogicalSide;

/**
 * Functional interface for receiving sync notifications for double values.
 * The EMPTY instance is a no-op singleton for use as a default/optional listener.
 */
@FunctionalInterface
public interface DoubleSyncListener {

    DoubleSyncListener EMPTY = (side, o, n) -> {
    };

    void onSync(LogicalSide side, double oldValue, double newValue);
}
