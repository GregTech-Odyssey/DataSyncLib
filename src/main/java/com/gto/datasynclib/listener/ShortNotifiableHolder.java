package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.ShortData;
import com.gto.datasynclib.util.holder.ShortHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Mutable short holder with integrated sync notification support.
 * Implements both IDataSerializable (for codec-based serialization) and ISyncNotifiable (for the listener pattern).
 * Supports sender/receiver listener callbacks for sync lifecycle events.
 */
public final class ShortNotifiableHolder extends ShortHolder implements IDataSerializable, ISyncNotifiable<ShortNotifiableHolder, ShortSyncListener> {

    public static ShortNotifiableHolder create() {
        return new ShortNotifiableHolder();
    }

    public static ShortNotifiableHolder create(short value) {
        return new ShortNotifiableHolder(value);
    }

    @Setter
    @Accessors(chain = true)
    private ShortSyncListener receiverListener = ShortSyncListener.EMPTY;
    @Setter
    @Accessors(chain = true)
    private ShortSyncListener senderListener = ShortSyncListener.EMPTY;

    private short lastValue;
    private boolean changed;

    private ShortNotifiableHolder() {
    }

    private ShortNotifiableHolder(short value) {
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
        data.writeShort(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        var oldValue = value;
        value = data.readShort();
        receiverListener.onSync(side, oldValue, value);
    }

    @Override
    public Data writeData() {
        return ShortData.valueOf(value);
    }

    @Override
    public void readData(@NotNull Data data, int dataVersion) {
        value = data.getShort();
    }
}
