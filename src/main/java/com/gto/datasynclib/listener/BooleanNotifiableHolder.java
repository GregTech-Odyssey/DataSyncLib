package com.gto.datasynclib.listener;

import com.gto.datasynclib.IDataSerializable;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datasream.data.ByteData;
import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.util.holder.BooleanHolder;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

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
    private boolean syncChange;

    private BooleanNotifiableHolder() {
    }

    private BooleanNotifiableHolder(boolean value) {
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
        data.writeBoolean(value);
        senderListener.onSync(side, lastValue, value);
        lastValue = value;
    }

    @Override
    public void readBuf(LogicalSide side, @NotNull FriendlyByteBuf data) {
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
