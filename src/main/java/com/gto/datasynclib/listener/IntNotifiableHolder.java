package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.IntData;
import com.gto.datasynclib.util.holder.IntHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Mutable int holder with integrated sync notification support.
 * Implements both IDataSerializable (for codec-based serialization) and ISyncNotifiable (for the listener pattern).
 * Supports sender/receiver listener callbacks for sync lifecycle events.
 */
public final class IntNotifiableHolder extends IntHolder implements IDataSerializable, ISyncNotifiable<IntNotifiableHolder, IntSyncListener> {

    public static IntNotifiableHolder create() {
        return new IntNotifiableHolder();
    }

    public static IntNotifiableHolder create(int value) {
        return new IntNotifiableHolder(value);
    }

    @Setter
    @Accessors(chain = true)
    private IntSyncListener receiverListener = IntSyncListener.EMPTY;
    @Setter
    @Accessors(chain = true)
    private IntSyncListener senderListener = IntSyncListener.EMPTY;

    private int lastValue;
    private boolean changed;

    private IntNotifiableHolder() {
    }

    private IntNotifiableHolder(int value) {
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
        data.writeVarInt(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        var oldValue = value;
        value = data.readVarInt();
        receiverListener.onSync(side, oldValue, value);
    }

    @Override
    public Data writeData() {
        return IntData.valueOf(value);
    }

    @Override
    public void readData(@NotNull Data data, int dataVersion) {
        value = data.getInt();
    }
}
