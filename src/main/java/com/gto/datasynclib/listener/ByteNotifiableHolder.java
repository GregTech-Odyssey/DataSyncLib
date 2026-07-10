package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.ByteData;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.util.holder.ByteHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Mutable byte holder with integrated sync notification support.
 * Implements both IDataSerializable (for codec-based serialization) and ISyncNotifiable (for the listener pattern).
 * Supports sender/receiver listener callbacks for sync lifecycle events.
 */
public final class ByteNotifiableHolder extends ByteHolder implements IDataSerializable, ISyncNotifiable<ByteNotifiableHolder, ByteSyncListener> {

    public static ByteNotifiableHolder create() {
        return new ByteNotifiableHolder();
    }

    public static ByteNotifiableHolder create(byte value) {
        return new ByteNotifiableHolder(value);
    }

    @Setter
    @Accessors(chain = true)
    private ByteSyncListener receiverListener = ByteSyncListener.EMPTY;
    @Setter
    @Accessors(chain = true)
    private ByteSyncListener senderListener = ByteSyncListener.EMPTY;

    private byte lastValue;
    private boolean changed;

    private ByteNotifiableHolder() {
    }

    private ByteNotifiableHolder(byte value) {
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
        data.writeByte(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        var oldValue = value;
        value = data.readByte();
        receiverListener.onSync(side, oldValue, value);
    }

    @Override
    public Data writeData() {
        return ByteData.valueOf(value);
    }

    @Override
    public void readData(@NotNull Data data, int dataVersion) {
        value = data.getByte();
    }
}
