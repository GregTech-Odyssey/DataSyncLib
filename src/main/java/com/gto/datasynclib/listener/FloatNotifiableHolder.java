package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.FloatData;
import com.gto.datasynclib.util.holder.FloatHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Mutable float holder with integrated sync notification support.
 * Implements both IDataSerializable (for codec-based serialization) and ISyncNotifiable (for the listener pattern).
 * Supports sender/receiver listener callbacks for sync lifecycle events.
 */
public final class FloatNotifiableHolder extends FloatHolder implements IDataSerializable, ISyncNotifiable<FloatNotifiableHolder, FloatSyncListener> {

    public static FloatNotifiableHolder create() {
        return new FloatNotifiableHolder();
    }

    public static FloatNotifiableHolder create(float value) {
        return new FloatNotifiableHolder(value);
    }

    @Setter
    @Accessors(chain = true)
    private FloatSyncListener receiverListener = FloatSyncListener.EMPTY;
    @Setter
    @Accessors(chain = true)
    private FloatSyncListener senderListener = FloatSyncListener.EMPTY;

    private float lastValue;
    private boolean changed;

    private FloatNotifiableHolder() {
    }

    private FloatNotifiableHolder(float value) {
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
        data.writeFloat(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        var oldValue = value;
        value = data.readFloat();
        receiverListener.onSync(side, oldValue, value);
    }

    @Override
    public Data writeData() {
        return FloatData.valueOf(value);
    }

    @Override
    public void readData(@NotNull Data data, int dataVersion) {
        value = data.getFloat();
    }
}
