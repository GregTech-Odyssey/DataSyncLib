package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.ByteData;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.util.holder.BooleanHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Mutable boolean holder with integrated sync notification support.
 * Implements both IDataSerializable (for codec-based serialization) and ISyncNotifiable (for the listener pattern).
 * Supports sender/receiver listener callbacks for sync lifecycle events.
 */
public final class BooleanNotifiableHolder extends BooleanHolder implements IDataSerializable, ISyncNotifiable<BooleanNotifiableHolder, BooleanSyncListener> {

    public static BooleanNotifiableHolder create() {
        return new BooleanNotifiableHolder();
    }

    public static BooleanNotifiableHolder create(boolean value) {
        return new BooleanNotifiableHolder(value);
    }

    @Setter
    @Accessors(chain = true)
    private BooleanSyncListener receiverListener = BooleanSyncListener.EMPTY;
    @Setter
    @Accessors(chain = true)
    private BooleanSyncListener senderListener = BooleanSyncListener.EMPTY;

    private boolean lastValue;
    private boolean changed;

    private BooleanNotifiableHolder() {
    }

    private BooleanNotifiableHolder(boolean value) {
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
        data.writeBoolean(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        var oldValue = value;
        value = data.readBoolean();
        receiverListener.onSync(side, oldValue, value);
    }

    @Override
    public Data writeData() {
        return ByteData.valueOf(value);
    }

    @Override
    public void readData(@NotNull Data data, int dataVersion) {
        value = data.getBoolean();
    }
}
