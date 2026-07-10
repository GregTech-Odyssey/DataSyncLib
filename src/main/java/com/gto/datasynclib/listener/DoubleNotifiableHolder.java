package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.datasream.data.DoubleData;
import com.gto.datasynclib.util.holder.DoubleHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

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
    private boolean syncChange;

    private DoubleNotifiableHolder() {
    }

    private DoubleNotifiableHolder(double value) {
        super(value);
    }

    @Override
    public void markAsChanged() {
        syncChange = true;
    }

    @Override
    public void clearChanged() {
        syncChange = false;
    }

    @Override
    public boolean isChanged() {
        return syncChange;
    }

    @Override
    public boolean detectChange() {
        return value != lastValue;
    }

    @Override
    public void writeBuf(LogicalSide side, @NotNull FriendlyByteBuf data) {
        data.writeDouble(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuf(LogicalSide side, @NotNull FriendlyByteBuf data) {
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
