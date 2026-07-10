package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.DoubleData;
import com.gto.datasynclib.util.holder.DoubleHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Mutable double holder with integrated sync notification support.
 * Implements both IDataSerializable (for codec-based serialization) and ISyncNotifiable (for the listener pattern).
 * Supports sender/receiver listener callbacks for sync lifecycle events.
 */
public final class DoubleNotifiableHolder extends DoubleHolder implements IDataSerializable, ISyncNotifiable<DoubleNotifiableHolder, DoubleSyncListener> {

    public static DoubleNotifiableHolder create() {
        return new DoubleNotifiableHolder();
    }

    public static DoubleNotifiableHolder create(double value) {
        return new DoubleNotifiableHolder(value);
    }

    @Setter
    @Accessors(chain = true)
    private DoubleSyncListener receiverListener = DoubleSyncListener.EMPTY;
    @Setter
    @Accessors(chain = true)
    private DoubleSyncListener senderListener = DoubleSyncListener.EMPTY;

    private double lastValue;
    private boolean changed;

    private DoubleNotifiableHolder() {
    }

    private DoubleNotifiableHolder(double value) {
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
        data.writeDouble(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuffer(LogicalSide side, @NotNull FriendlyByteBuf data) {
        var oldValue = value;
        value = data.readDouble();
        receiverListener.onSync(side, oldValue, value);
    }

    @Override
    public Data writeData() {
        return DoubleData.valueOf(value);
    }

    @Override
    public void readData(@NotNull Data data, int dataVersion) {
        value = data.getDouble();
    }
}
