package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.LongData;
import com.gto.datasynclib.util.holder.LongHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Mutable long holder with integrated sync notification support.
 * Implements both IDataSerializable (for codec-based serialization) and ISyncNotifiable (for the listener pattern).
 * Supports sender/receiver listener callbacks for sync lifecycle events.
 */
public final class LongNotifiableHolder extends LongHolder implements IDataSerializable, ISyncNotifiable<LongNotifiableHolder, LongSyncListener> {

    public static LongNotifiableHolder create() {
        return new LongNotifiableHolder();
    }

    public static LongNotifiableHolder create(long value) {
        return new LongNotifiableHolder(value);
    }

    @Setter
    @Accessors(chain = true)
    private LongSyncListener receiverListener = LongSyncListener.EMPTY;
    @Setter
    @Accessors(chain = true)
    private LongSyncListener senderListener = LongSyncListener.EMPTY;

    private long lastValue;
    private boolean changed;

    private LongNotifiableHolder() {
    }

    private LongNotifiableHolder(long value) {
        super(value);
    }

    @Override
    public void markAsChanged() {
        changed = true;
    }

    @Override
    public void clearChanged() {
        changed = false;
    }

    @Override
    public boolean isChanged() {
        return changed;
    }

    @Override
    public boolean detectChange() {
        return value != lastValue;
    }

    @Override
    public void writeBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        data.writeLong(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        var oldValue = value;
        value = data.readLong();
        receiverListener.onSync(side, oldValue, value);
    }

    @Override
    public Data writeData() {
        return LongData.valueOf(value);
    }

    @Override
    public void readData(@NotNull Data data, int dataVersion) {
        value = data.getLong();
    }
}
