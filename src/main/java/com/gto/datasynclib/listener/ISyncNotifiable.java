package com.gto.datasynclib.listener;

import org.jetbrains.annotations.NotNull;

public interface ISyncNotifiable<T, L> {

    T setReceiverListener(@NotNull L receiverListener);

    T setSenderListener(@NotNull L senderListener);
}
